import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import * as piAgentCore from "@earendil-works/pi-agent-core";
import * as piAiCompat from "@earendil-works/pi-ai/compat";
import * as piAiOauth from "@earendil-works/pi-ai/oauth";
import * as piCodingAgent from "@earendil-works/pi-coding-agent";
import {
  AuthStorage,
  createEventBus,
  createExtensionRuntime,
  DefaultPackageManager,
  ExtensionRunner,
  ModelRegistry,
  SessionManager,
  SettingsManager,
  wrapRegisteredTools,
  type Extension,
  type ExtensionFactory,
  type ExtensionRuntime,
} from "@earendil-works/pi-coding-agent";
import * as piTui from "@earendil-works/pi-tui";
import { createJiti } from "jiti/static";
import * as typebox from "typebox";
import * as typeboxCompile from "typebox/compile";
import * as typeboxValue from "typebox/value";
import { loadExtensionFromFactory } from "../node_modules/@earendil-works/pi-coding-agent/dist/core/extensions/loader.js";
import {
  aetherAppExtensionCountForManifest,
  aetherExtensionApiModule,
} from "./aether-extensions.js";

const EXTENSION_FILE_PATTERN = /\.(?:[cm]?[jt]s)$/i;
const INDEX_FILE_NAMES = [
  "index.ts",
  "index.js",
  "index.mts",
  "index.mjs",
  "index.cts",
  "index.cjs",
];
const PI_AGENT_DIRECTORY = path.join(os.homedir(), ".pi", "agent");

const virtualModules: Record<string, unknown> = {
  typebox,
  "typebox/compile": typeboxCompile,
  "typebox/value": typeboxValue,
  "@sinclair/typebox": typebox,
  "@sinclair/typebox/compile": typeboxCompile,
  "@sinclair/typebox/value": typeboxValue,
  "@earendil-works/pi-agent-core": piAgentCore,
  "@earendil-works/pi-ai": piAiCompat,
  "@earendil-works/pi-ai/compat": piAiCompat,
  "@earendil-works/pi-ai/oauth": piAiOauth,
  "@earendil-works/pi-coding-agent": piCodingAgent,
  "@earendil-works/pi-tui": piTui,
  "@mariozechner/pi-agent-core": piAgentCore,
  "@mariozechner/pi-ai": piAiCompat,
  "@mariozechner/pi-ai/compat": piAiCompat,
  "@mariozechner/pi-ai/oauth": piAiOauth,
  "@mariozechner/pi-coding-agent": piCodingAgent,
  "@mariozechner/pi-tui": piTui,
  "@baimoqilin/aether-extension-api": aetherExtensionApiModule,
  "@aether/extension-api": aetherExtensionApiModule,
  "@aether/android-extension": aetherExtensionApiModule,
};

export interface AetherExtensionLoadError {
  path: string;
  error: string;
}

export interface AetherExtensionRuntime {
  runner: ExtensionRunner;
  runtime: ExtensionRuntime;
  sessionManager: SessionManager;
  modelRegistry: ModelRegistry;
  paths: string[];
  errors: AetherExtensionLoadError[];
}

export interface AetherExtensionLoadOptions {
  disabledExtensionPaths?: string[];
  disabledPackageSources?: string[];
}

export interface AetherInstalledExtensionPackage {
  source: string;
  scope: "user" | "project";
  filtered: boolean;
  installedPath?: string;
  name: string;
  version: string;
  description: string;
  extensionCount: number;
  aetherExtensionCount: number;
  nativeEntrypointCount: number;
  skillCount: number;
  promptCount: number;
  themeCount: number;
  skillPaths: string[];
}

type AetherConfiguredPackage = ReturnType<
  DefaultPackageManager["listConfiguredPackages"]
>[number];

function readJsonFile(filePath: string): Record<string, unknown> | undefined {
  try {
    const parsed = JSON.parse(fs.readFileSync(filePath, "utf8"));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}

function isPathDisabled(
  candidatePath: string,
  disabledPaths: Set<string>,
): boolean {
  const candidate = path.resolve(candidatePath);
  return [...disabledPaths].some((disabledPath) => {
    const relative = path.relative(disabledPath, candidate);
    return relative === "" || (
      !relative.startsWith(`..${path.sep}`) &&
      relative !== ".." &&
      !path.isAbsolute(relative)
    );
  });
}

function packageExtensionPaths(directory: string): string[] {
  const manifest = readJsonFile(path.join(directory, "package.json"));
  const pi = manifest?.pi;
  if (pi && typeof pi === "object" && !Array.isArray(pi)) {
    const configured = (pi as Record<string, unknown>).extensions;
    if (Array.isArray(configured)) {
      const paths = configured
        .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
        .map((entry) => path.resolve(directory, entry));
      if (paths.length > 0) return paths;
    }
  }
  if (aetherAppExtensionCountForManifest(manifest) > 0) return [];
  for (const name of INDEX_FILE_NAMES) {
    const candidate = path.join(directory, name);
    if (fs.existsSync(candidate)) return [candidate];
  }
  return [];
}

function extensionEntriesAt(candidatePath: string): string[] {
  let stat: fs.Stats;
  try {
    stat = fs.statSync(candidatePath);
  } catch {
    return [];
  }
  if (stat.isFile()) {
    return EXTENSION_FILE_PATTERN.test(candidatePath) ? [path.resolve(candidatePath)] : [];
  }
  if (!stat.isDirectory()) return [];
  return packageExtensionPaths(candidatePath);
}

function extensionEntriesInRoot(root: string): string[] {
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .sort((left, right) => left.name.localeCompare(right.name))
    .flatMap((entry) => extensionEntriesAt(path.join(root, entry.name)));
}

export function discoverAetherExtensionPaths(
  cwd: string,
  configuredPaths: string[] = [],
): string[] {
  const roots = [
    path.join(os.homedir(), ".pi", "agent", "extensions"),
    path.join(cwd, ".pi", "extensions"),
    path.join(os.homedir(), ".aether", "extensions"),
  ];
  const discovered = [
    ...roots.flatMap(extensionEntriesInRoot),
    ...configuredPaths.flatMap((configuredPath) =>
      extensionEntriesAt(path.resolve(cwd, configuredPath)),
    ),
  ];
  return [...new Set(discovered.map((entry) => path.resolve(entry)))];
}

function createPackageManager(cwd: string): DefaultPackageManager {
  const settingsManager = SettingsManager.create(cwd, PI_AGENT_DIRECTORY, {
    projectTrusted: false,
  });
  return new DefaultPackageManager({
    cwd,
    agentDir: PI_AGENT_DIRECTORY,
    settingsManager,
  });
}

async function discoverPackageExtensionPaths(
  cwd: string,
  disabledPackageSources: Set<string> = new Set(),
): Promise<string[]> {
  const resolved = await createPackageManager(cwd).resolve();
  return resolved.extensions
    .filter((entry) => entry.enabled)
    .filter((entry) => !disabledPackageSources.has(entry.metadata.source))
    .map((entry) => path.resolve(entry.path));
}

function packageManifest(configuredPackage: AetherConfiguredPackage): Record<string, unknown> | undefined {
  if (!configuredPackage.installedPath) return undefined;
  return readJsonFile(path.join(configuredPackage.installedPath, "package.json"));
}

function manifestExtensionCount(manifest: Record<string, unknown> | undefined): number {
  const pi = manifest?.pi;
  if (!pi || typeof pi !== "object" || Array.isArray(pi)) return 0;
  const extensions = (pi as Record<string, unknown>).extensions;
  return Array.isArray(extensions)
    ? extensions.filter((entry) => typeof entry === "string").length
    : 0;
}

function manifestNativeEntrypointCount(
  manifest: Record<string, unknown> | undefined,
): number {
  const aether = manifest?.aether;
  if (!aether || typeof aether !== "object" || Array.isArray(aether)) return 0;
  const native = (aether as Record<string, unknown>).native;
  if (!native || typeof native !== "object" || Array.isArray(native)) return 0;
  const nativeManifest = native as Record<string, unknown>;
  if (nativeManifest.enabled === false) return 0;
  const countConfigured = (configured: unknown): number => {
    if (Array.isArray(configured)) {
      return configured.filter((entry) =>
        typeof entry === "string" && entry.trim().length > 0
      ).length;
    }
    return typeof configured === "string" && configured.trim().length > 0
      ? 1
      : 0;
  };
  return countConfigured(nativeManifest.entrypoints) ||
    countConfigured(nativeManifest.entrypoint);
}

interface AetherPackageResources {
  extensions: string[];
  skills: string[];
  prompts: string[];
  themes: string[];
}

function packageResourcesForSource(
  source: string,
  resolved: Awaited<ReturnType<DefaultPackageManager["resolve"]>>,
): AetherPackageResources {
  const pathsFor = (
    entries: Awaited<ReturnType<DefaultPackageManager["resolve"]>>["extensions"],
  ): string[] =>
    entries
      .filter((entry) => entry.enabled && entry.metadata.source === source)
      .map((entry) => path.resolve(entry.path));
  return {
    extensions: pathsFor(resolved.extensions),
    skills: pathsFor(resolved.skills),
    prompts: pathsFor(resolved.prompts),
    themes: pathsFor(resolved.themes),
  };
}

function installedPackagePayload(
  configuredPackage: AetherConfiguredPackage,
  resources: AetherPackageResources,
): AetherInstalledExtensionPackage {
  const manifest = packageManifest(configuredPackage);
  return {
    source: configuredPackage.source,
    scope: configuredPackage.scope,
    filtered: configuredPackage.filtered,
    installedPath: configuredPackage.installedPath,
    name:
      (typeof manifest?.name === "string" && manifest.name.trim()) ||
      configuredPackage.source.replace(/^npm:/, ""),
    version: typeof manifest?.version === "string" ? manifest.version : "",
    description: typeof manifest?.description === "string" ? manifest.description : "",
    extensionCount: resources.extensions.length || manifestExtensionCount(manifest),
    aetherExtensionCount: aetherAppExtensionCountForManifest(manifest),
    nativeEntrypointCount: manifestNativeEntrypointCount(manifest),
    skillCount: resources.skills.length,
    promptCount: resources.prompts.length,
    themeCount: resources.themes.length,
    skillPaths: resources.skills,
  };
}

export async function listAetherExtensionPackages(
  cwd: string,
): Promise<AetherInstalledExtensionPackage[]> {
  const packageManager = createPackageManager(cwd);
  const resolved = await packageManager.resolve();
  return packageManager
    .listConfiguredPackages()
    .filter((configuredPackage) => configuredPackage.scope === "user")
    .map((configuredPackage) =>
      installedPackagePayload(
        configuredPackage,
        packageResourcesForSource(configuredPackage.source, resolved),
      ),
    )
    .sort((left, right) => left.name.localeCompare(right.name));
}

function requireNpmPackageSource(source: string): string {
  const normalized = source.trim();
  if (!normalized.startsWith("npm:") || normalized.length <= 4 || /\s/.test(normalized)) {
    throw new Error("Pi packages must use an npm: source.");
  }
  return normalized;
}

export async function installAetherExtensionPackage(
  cwd: string,
  source: string,
): Promise<void> {
  await createPackageManager(cwd).installAndPersist(requireNpmPackageSource(source));
}

export async function removeAetherExtensionPackage(
  cwd: string,
  source: string,
): Promise<boolean> {
  return createPackageManager(cwd).removeAndPersist(requireNpmPackageSource(source));
}

export async function updateAetherExtensionPackage(
  cwd: string,
  source: string,
): Promise<void> {
  await createPackageManager(cwd).update(requireNpmPackageSource(source));
}

async function loadFactory(extensionPath: string): Promise<ExtensionFactory> {
  const jiti = createJiti(import.meta.url, {
    moduleCache: false,
    fsCache: false,
    tryNative: false,
    virtualModules,
  });
  const factory = await jiti.import<unknown>(extensionPath, { default: true });
  if (typeof factory !== "function") {
    throw new Error("Extension does not export a default factory function.");
  }
  return factory as ExtensionFactory;
}

export async function loadAetherExtensions(
  cwd: string,
  configuredPaths: string[] = [],
  loadOptions: AetherExtensionLoadOptions = {},
): Promise<AetherExtensionRuntime> {
  const disabledExtensionPaths = new Set(
    (loadOptions.disabledExtensionPaths ?? []).map((entry) => path.resolve(entry)),
  );
  const disabledPackageSources = new Set(loadOptions.disabledPackageSources ?? []);
  const paths = [
    ...discoverAetherExtensionPaths(cwd, configuredPaths)
      .filter((entry) => !isPathDisabled(entry, disabledExtensionPaths)),
    ...(await discoverPackageExtensionPaths(cwd, disabledPackageSources)),
  ].filter((entry, index, entries) => entries.indexOf(entry) === index);
  const runtime = createExtensionRuntime();
  const eventBus = createEventBus();
  const extensions: Extension[] = [];
  const errors: AetherExtensionLoadError[] = [];

  for (const extensionPath of paths) {
    try {
      const factory = await loadFactory(extensionPath);
      extensions.push(
        await loadExtensionFromFactory(factory, cwd, eventBus, runtime, extensionPath),
      );
    } catch (error) {
      errors.push({
        path: extensionPath,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  }

  const sessionManager = SessionManager.inMemory(cwd);
  const authStorage = AuthStorage.inMemory();
  const modelRegistry = ModelRegistry.inMemory(authStorage);
  const runner = new ExtensionRunner(
    extensions,
    runtime,
    cwd,
    sessionManager,
    modelRegistry,
  );
  runner.setUIContext(undefined, "print");
  return {
    runner,
    runtime,
    sessionManager,
    modelRegistry,
    paths,
    errors,
  };
}

export function extensionTools(extensionRuntime: AetherExtensionRuntime) {
  return wrapRegisteredTools(
    extensionRuntime.runner.getAllRegisteredTools(),
    extensionRuntime.runner,
  );
}
