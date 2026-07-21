import { createHash } from "node:crypto";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import {
  DefaultPackageManager,
  SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { createJiti } from "jiti/static";
import type {
  AetherActionContext,
  AetherComponentDefinition,
  AetherComponentMode,
  AetherEventContext,
  AetherExtensionAPI,
  AetherExtensionFactory,
  AetherJsonObject,
  AetherPageDefinition,
  AetherRenderContext,
  AetherSurfaceDefinition,
  AetherUi,
  AetherView,
} from "../../packages/extension-api/src/index.js";
export type {
  AetherActionContext,
  AetherComponentDefinition,
  AetherComponentMode,
  AetherEventContext,
  AetherExtensionAPI,
  AetherExtensionFactory,
  AetherJsonObject,
  AetherPageDefinition,
  AetherRenderContext,
  AetherSurfaceDefinition,
  AetherUi,
  AetherView,
} from "../../packages/extension-api/src/index.js";

export interface AetherExtensionTransport {
  requestHost(method: string, args: AetherJsonObject): Promise<AetherJsonObject>;
  invalidate(version: number): void;
  notify(message: string, level: "info" | "warning" | "error"): void;
}

interface AetherExtensionDescriptor {
  id: string;
  name: string;
  path: string;
  explicit: boolean;
  packageSource?: string;
  compatibilityError?: string;
}

interface DiscoveredAetherEntry {
  path: string;
  name: string;
  explicit: boolean;
  packageSource?: string;
  compatibilityError?: string;
}

interface LoadedAetherExtension extends AetherExtensionDescriptor {
  cleanup?: () => void | Promise<void>;
}

interface RegisteredSurface {
  id: string;
  extension: LoadedAetherExtension;
  slot: string;
  order: number;
  render: AetherSurfaceDefinition["render"];
}

interface RegisteredComponent {
  id: string;
  extension: LoadedAetherExtension;
  target: string;
  mode: AetherComponentMode;
  order: number;
  render: AetherSurfaceDefinition["render"];
}

interface RegisteredPage {
  id: string;
  localId: string;
  extension: LoadedAetherExtension;
  title: string;
  subtitle: string;
  icon: string;
  order: number;
  render: AetherSurfaceDefinition["render"];
}

interface RegisteredAction {
  extension: LoadedAetherExtension;
  localId: string;
  handler: (
    payload: AetherJsonObject,
    context: AetherActionContext,
  ) => unknown | Promise<unknown>;
}

interface RegisteredEventHandler {
  extension: LoadedAetherExtension;
  handler: (
    payload: AetherJsonObject,
    context: AetherEventContext,
  ) => unknown | Promise<unknown>;
}

interface AetherExtensionError {
  path: string;
  extension_id?: string;
  phase: "load" | "render" | "action" | "event";
  error: string;
}

interface AetherRuntimeState {
  cwd: string;
  extensions: LoadedAetherExtension[];
  surfaces: Map<string, RegisteredSurface>;
  components: Map<string, RegisteredComponent>;
  pages: Map<string, RegisteredPage>;
  actions: Map<string, RegisteredAction>;
  events: Map<string, RegisteredEventHandler[]>;
  errors: AetherExtensionError[];
}

export interface AetherAppExtensionLoadResult {
  reloaded: boolean;
  errors: AetherExtensionError[];
}

const AETHER_API_VERSION = 2;
const AETHER_AGENT_DIRECTORY = path.join(os.homedir(), ".pi", "agent");
const AETHER_EXTENSION_ROOT = path.join(os.homedir(), ".aether", "extensions");
const PI_EXTENSION_ROOT = path.join(AETHER_AGENT_DIRECTORY, "extensions");
const AETHER_STORAGE_FILE = path.join(os.homedir(), ".aether", "app-extension-state.json");
const EXTENSION_FILE_PATTERN = /\.(?:[cm]?[jt]s)$/i;
const INDEX_FILE_NAMES = [
  "index.ts",
  "index.js",
  "index.mts",
  "index.mjs",
  "index.cts",
  "index.cjs",
];

const emptyTransport: AetherExtensionTransport = {
  async requestHost() {
    throw new Error("The Aether Android host is not connected.");
  },
  invalidate() {},
  notify() {},
};

let transport: AetherExtensionTransport = emptyTransport;
let runtime: AetherRuntimeState = createEmptyRuntime(process.cwd());
let runtimeVersion = 0;
let latestHostContext: AetherJsonObject = {};
let persistedStorage = readPersistedStorage();
let runtimeOperationQueue: Promise<void> = Promise.resolve();
let extensionWatchers: fs.FSWatcher[] = [];
let extensionWatchTimer: NodeJS.Timeout | undefined;
let currentLoadOptions: {
  disabledExtensionPaths?: string[];
  disabledPackageSources?: string[];
} = {};

function createEmptyRuntime(cwd: string): AetherRuntimeState {
  return {
    cwd,
    extensions: [],
    surfaces: new Map(),
    components: new Map(),
    pages: new Map(),
    actions: new Map(),
    events: new Map(),
    errors: [],
  };
}

function asObject(value: unknown): AetherJsonObject {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as AetherJsonObject)
    : {};
}

function cloneJson<T>(value: T): T {
  if (value === undefined) return value;
  return JSON.parse(JSON.stringify(value)) as T;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function readJsonFile(filePath: string): AetherJsonObject | undefined {
  try {
    return asObject(JSON.parse(fs.readFileSync(filePath, "utf8")));
  } catch {
    return undefined;
  }
}

function readPersistedStorage(): Record<string, AetherJsonObject> {
  return asObject(readJsonFile(AETHER_STORAGE_FILE)) as Record<string, AetherJsonObject>;
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

function writePersistedStorage(): void {
  fs.mkdirSync(path.dirname(AETHER_STORAGE_FILE), { recursive: true });
  const temporaryPath = `${AETHER_STORAGE_FILE}.${process.pid}.tmp`;
  fs.writeFileSync(temporaryPath, JSON.stringify(persistedStorage, null, 2), "utf8");
  fs.renameSync(temporaryPath, AETHER_STORAGE_FILE);
}

function extensionStorage(extensionId: string): AetherJsonObject {
  const current = asObject(persistedStorage[extensionId]);
  persistedStorage[extensionId] = current;
  return current;
}

function bumpVersion(): void {
  runtimeVersion += 1;
  transport.invalidate(runtimeVersion);
}

async function withRuntimeLock<T>(operation: () => Promise<T>): Promise<T> {
  const previous = runtimeOperationQueue;
  let release: () => void = () => {};
  runtimeOperationQueue = new Promise<void>((resolve) => {
    release = resolve;
  });
  await previous;
  try {
    return await operation();
  } finally {
    release();
  }
}

function recordRuntimeError(
  runtimeState: AetherRuntimeState,
  error: AetherExtensionError,
): void {
  runtimeState.errors.push(error);
  if (runtimeState.errors.length > 100) {
    runtimeState.errors.splice(0, runtimeState.errors.length - 100);
  }
}

function stableExtensionId(name: string, entryPath: string): string {
  const slug = name
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "") || "extension";
  const hash = createHash("sha256").update(path.resolve(entryPath)).digest("hex").slice(0, 10);
  return `${slug}:${hash}`;
}

function manifestApiCompatibilityError(
  manifest: AetherJsonObject | undefined,
): string | undefined {
  const aether = manifest?.aether;
  if (!aether || typeof aether !== "object" || Array.isArray(aether)) {
    return undefined;
  }
  const configured = (aether as AetherJsonObject).api;
  if (configured === undefined) return undefined;
  if (typeof configured === "number") {
    if (!Number.isInteger(configured) || configured <= 0) {
      return "aether.api must be a positive integer or an API range object.";
    }
    return configured === AETHER_API_VERSION
      ? undefined
      : `Requires Aether Script API ${configured}, but this runtime provides ${AETHER_API_VERSION}.`;
  }
  if (!configured || typeof configured !== "object" || Array.isArray(configured)) {
    return "aether.api must be a positive integer or an API range object.";
  }
  const api = configured as AetherJsonObject;
  const minimum = api.min;
  const maximum = api.max;
  const allowNewer = api.allowNewer === true;
  if (
    minimum !== undefined &&
    (!Number.isInteger(minimum) || Number(minimum) <= 0)
  ) {
    return "aether.api.min must be a positive integer.";
  }
  if (
    maximum !== undefined &&
    (!Number.isInteger(maximum) || Number(maximum) <= 0)
  ) {
    return "aether.api.max must be a positive integer.";
  }
  if (minimum === undefined && maximum === undefined) {
    return "aether.api must declare min, max, or both.";
  }
  if (
    minimum !== undefined &&
    maximum !== undefined &&
    Number(minimum) > Number(maximum)
  ) {
    return "aether.api.min cannot be greater than aether.api.max.";
  }
  if (minimum !== undefined && AETHER_API_VERSION < Number(minimum)) {
    return `Requires Aether Script API ${minimum} or newer, but this runtime provides ${AETHER_API_VERSION}.`;
  }
  if (
    maximum !== undefined &&
    AETHER_API_VERSION > Number(maximum) &&
    !allowNewer
  ) {
    return `Supports Aether Script API through ${maximum}, but this runtime provides ${AETHER_API_VERSION}.`;
  }
  return undefined;
}

function manifestAetherEntries(
  directory: string,
  packageSource?: string,
): Array<{
  path: string;
  name: string;
  explicit: true;
  compatibilityError?: string;
}> {
  const manifest = readJsonFile(path.join(directory, "package.json"));
  const aether = asObject(manifest?.aether);
  const configured = aether.extensions;
  if (!Array.isArray(configured)) return [];
  const compatibilityError = manifestApiCompatibilityError(manifest);
  const packageName =
    (typeof manifest?.name === "string" && manifest.name.trim()) ||
    path.basename(directory);
  return configured
    .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
    .map((entry) => ({
      path: path.resolve(directory, entry),
      name: packageName,
      explicit: true as const,
      packageSource,
      compatibilityError,
    }))
    .filter((entry) => fs.existsSync(entry.path));
}

function implicitAetherEntry(
  candidatePath: string,
): Array<{
  path: string;
  name: string;
  explicit: false;
  compatibilityError?: string;
}> {
  let stat: fs.Stats;
  try {
    stat = fs.statSync(candidatePath);
  } catch {
    return [];
  }
  if (stat.isFile()) {
    return EXTENSION_FILE_PATTERN.test(candidatePath)
      ? [{ path: path.resolve(candidatePath), name: path.basename(candidatePath, path.extname(candidatePath)), explicit: false }]
      : [];
  }
  if (!stat.isDirectory()) return [];
  const configured = manifestAetherEntries(candidatePath);
  if (configured.length > 0) return [];
  const manifest = readJsonFile(path.join(candidatePath, "package.json"));
  const compatibilityError = manifestApiCompatibilityError(manifest);
  for (const fileName of INDEX_FILE_NAMES) {
    const entryPath = path.join(candidatePath, fileName);
    if (fs.existsSync(entryPath)) {
      return [{
        path: entryPath,
        name: path.basename(candidatePath),
        explicit: false,
        compatibilityError,
      }];
    }
  }
  return [];
}

function entriesInRoot(
  root: string,
): DiscoveredAetherEntry[] {
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((entry) => !entry.name.startsWith(".aether-import-"))
    .sort((left, right) => left.name.localeCompare(right.name))
    .flatMap<DiscoveredAetherEntry>((entry) => {
      const candidatePath = path.join(root, entry.name);
      if (entry.isDirectory()) {
        const configured = manifestAetherEntries(candidatePath);
        if (configured.length > 0) return configured;
      }
      return implicitAetherEntry(candidatePath);
    });
}

function createPackageManager(cwd: string): DefaultPackageManager {
  const settingsManager = SettingsManager.create(cwd, AETHER_AGENT_DIRECTORY, {
    projectTrusted: false,
  });
  return new DefaultPackageManager({
    cwd,
    agentDir: AETHER_AGENT_DIRECTORY,
    settingsManager,
  });
}

async function packageAetherEntries(
  cwd: string,
): Promise<DiscoveredAetherEntry[]> {
  const packageManager = createPackageManager(cwd);
  await packageManager.resolve();
  return packageManager
    .listConfiguredPackages()
    .filter((configuredPackage) => configuredPackage.scope === "user")
    .flatMap((configuredPackage) => {
      if (!configuredPackage.installedPath) return [];
      return manifestAetherEntries(
        configuredPackage.installedPath,
        configuredPackage.source,
      ).map((entry) => ({
        ...entry,
        packageSource: configuredPackage.source,
      }));
    });
}

async function discoverAetherExtensionEntries(
  cwd: string,
  loadOptions: {
    disabledExtensionPaths?: string[];
    disabledPackageSources?: string[];
  } = {},
): Promise<AetherExtensionDescriptor[]> {
  const disabledExtensionPaths = new Set(
    (loadOptions.disabledExtensionPaths ?? []).map((entry) => path.resolve(entry)),
  );
  const disabledPackageSources = new Set(loadOptions.disabledPackageSources ?? []);
  const entries = [
    ...entriesInRoot(AETHER_EXTENSION_ROOT),
    ...entriesInRoot(PI_EXTENSION_ROOT),
    ...(await packageAetherEntries(cwd)),
  ];
  const seen = new Set<string>();
  return entries.flatMap((entry) => {
    const resolvedPath = path.resolve(entry.path);
    if (
      isPathDisabled(resolvedPath, disabledExtensionPaths) ||
      (entry.packageSource && disabledPackageSources.has(entry.packageSource))
    ) {
      return [];
    }
    if (seen.has(resolvedPath)) return [];
    seen.add(resolvedPath);
    return [{
      id: stableExtensionId(entry.name, resolvedPath),
      name: entry.name,
      path: resolvedPath,
      explicit: entry.explicit,
      packageSource: entry.packageSource,
      compatibilityError: entry.compatibilityError,
    }];
  });
}

function normalizeSurfaceDefinition(
  definition:
    | AetherSurfaceDefinition
    | AetherView
    | ((context: AetherRenderContext) => AetherView | Promise<AetherView>),
): AetherSurfaceDefinition {
  if (typeof definition === "function") return { render: definition };
  if (
    definition &&
    typeof definition === "object" &&
    !Array.isArray(definition) &&
    (
      Object.prototype.hasOwnProperty.call(definition, "render") ||
      Object.prototype.hasOwnProperty.call(definition, "tree") ||
      Object.prototype.hasOwnProperty.call(definition, "order") ||
      Object.prototype.hasOwnProperty.call(definition, "id") ||
      Object.prototype.hasOwnProperty.call(definition, "mode")
    )
  ) {
    return definition as AetherSurfaceDefinition;
  }
  return { tree: definition as AetherView };
}

function scopedId(extensionId: string, localId: string): string {
  return `${extensionId}:${localId.trim()}`;
}

function renderValue(definition: AetherSurfaceDefinition): AetherSurfaceDefinition["render"] {
  return definition.render ?? definition.tree;
}

function createApiEventRegistration(
  runtimeState: AetherRuntimeState,
  extension: LoadedAetherExtension,
  eventName: string,
  handler: RegisteredEventHandler["handler"],
): () => void {
  const registration = { extension, handler };
  const handlers = runtimeState.events.get(eventName) ?? [];
  handlers.push(registration);
  runtimeState.events.set(eventName, handlers);
  return () => {
    const current = runtimeState.events.get(eventName) ?? handlers;
    const updated = current.filter((entry) => entry !== registration);
    if (updated.length > 0) runtimeState.events.set(eventName, updated);
    else runtimeState.events.delete(eventName);
  };
}

function createRenderContext(extension: LoadedAetherExtension): AetherRenderContext {
  return {
    ...cloneJson(latestHostContext),
    extension: {
      id: extension.id,
      name: extension.name,
      path: extension.path,
    },
    storage: cloneJson(extensionStorage(extension.id)),
  };
}

function createApi(
  runtimeState: AetherRuntimeState,
  extension: LoadedAetherExtension,
): AetherExtensionAPI {
  const invalidate = () => {
    if (runtime === runtimeState) bumpVersion();
  };
  return {
    apiVersion: AETHER_API_VERSION,
    extension: {
      id: extension.id,
      name: extension.name,
      path: extension.path,
    },
    ui,
    host: {
      invoke(method, args = {}) {
        return transport.requestHost(method, cloneJson(args));
      },
    },
    services: {
      list() {
        return transport.requestHost("kernel.listServices", {});
      },
      describe(service) {
        return transport.requestHost("kernel.describeService", { service });
      },
      invoke(service, method, args = {}) {
        return transport.requestHost("service.invoke", {
          service,
          method,
          args: cloneJson(args),
        });
      },
    },
    state: {
      get(path = "") {
        return transport.requestHost("state.get", { path });
      },
      patch(path, value) {
        return transport.requestHost("state.transaction", {
          operations: [{ op: "set", path, value: cloneJson(value) }],
        });
      },
      transaction(operations) {
        return transport.requestHost("state.transaction", {
          operations: cloneJson(operations),
        });
      },
    },
    storage: {
      get<T>(key: string, fallback?: T): T {
        const storage = extensionStorage(extension.id);
        return (Object.prototype.hasOwnProperty.call(storage, key)
          ? cloneJson(storage[key])
          : fallback) as T;
      },
      set(key: string, value: unknown) {
        extensionStorage(extension.id)[key] = cloneJson(value);
        writePersistedStorage();
        invalidate();
      },
      delete(key: string) {
        delete extensionStorage(extension.id)[key];
        writePersistedStorage();
        invalidate();
      },
      clear() {
        persistedStorage[extension.id] = {};
        writePersistedStorage();
        invalidate();
      },
      snapshot() {
        return cloneJson(extensionStorage(extension.id));
      },
    },
    registerSurface(slot, rawDefinition) {
      const definition = normalizeSurfaceDefinition(rawDefinition);
      const localId = definition.id?.trim() || `${slot}-${runtimeState.surfaces.size + 1}`;
      const id = scopedId(extension.id, localId);
      runtimeState.surfaces.set(id, {
        id,
        extension,
        slot: slot.trim(),
        order: Number.isFinite(definition.order) ? Number(definition.order) : 0,
        render: renderValue(definition),
      });
      invalidate();
      return () => {
        if (runtimeState.surfaces.delete(id)) invalidate();
      };
    },
    registerComponent(target, rawDefinition) {
      const definition = normalizeSurfaceDefinition(rawDefinition) as AetherComponentDefinition;
      const normalizedTarget = target.trim();
      if (!normalizedTarget) {
        throw new Error("Aether extension components require a target.");
      }
      const localId = definition.id?.trim() ||
        `${normalizedTarget}-${runtimeState.components.size + 1}`;
      const id = scopedId(extension.id, localId);
      const requestedMode = definition.mode?.trim().toLowerCase();
      const mode: AetherComponentMode = (
        requestedMode === "before" ||
        requestedMode === "after" ||
        requestedMode === "replace" ||
        requestedMode === "wrap" ||
        requestedMode === "hide"
      ) ? requestedMode : "wrap";
      runtimeState.components.set(id, {
        id,
        extension,
        target: normalizedTarget,
        mode,
        order: Number.isFinite(definition.order) ? Number(definition.order) : 0,
        render: mode === "hide" ? undefined : renderValue(definition),
      });
      invalidate();
      return () => {
        if (runtimeState.components.delete(id)) invalidate();
      };
    },
    registerPage(definition) {
      const localId = definition.id.trim();
      if (!localId) throw new Error("Aether extension pages require an id.");
      if (!definition.title.trim()) throw new Error("Aether extension pages require a title.");
      const id = scopedId(extension.id, localId);
      runtimeState.pages.set(id, {
        id,
        localId,
        extension,
        title: definition.title,
        subtitle: definition.subtitle ?? "",
        icon: definition.icon ?? "extension",
        order: Number.isFinite(definition.order) ? Number(definition.order) : 0,
        render: renderValue(definition),
      });
      invalidate();
      return () => {
        if (runtimeState.pages.delete(id)) invalidate();
      };
    },
    registerAction(id, handler) {
      const localId = id.trim();
      if (!localId) throw new Error("Aether extension actions require an id.");
      const idScoped = scopedId(extension.id, localId);
      runtimeState.actions.set(idScoped, { extension, localId, handler });
      return () => {
        runtimeState.actions.delete(idScoped);
      };
    },
    on(event, handler) {
      const eventName = event.trim();
      if (!eventName) throw new Error("Aether extension event handlers require an event name.");
      return createApiEventRegistration(runtimeState, extension, eventName, handler);
    },
    intercept(operation, handler) {
      const operationName = operation.trim();
      if (!operationName) {
        throw new Error("Aether operation interceptors require an operation name.");
      }
      return createApiEventRegistration(
        runtimeState,
        extension,
        `operation:${operationName}`,
        handler,
      );
    },
    invalidate,
    notify(message, level = "info") {
      transport.notify(message, level);
    },
  };
}

async function loadFactory(
  descriptor: AetherExtensionDescriptor,
): Promise<AetherExtensionFactory | undefined> {
  const jiti = createJiti(import.meta.url, {
    moduleCache: false,
    fsCache: false,
    tryNative: false,
    virtualModules: {
      "@baimoqilin/aether-extension-api": aetherExtensionApiModule,
      "@aether/extension-api": aetherExtensionApiModule,
      "@aether/android-extension": aetherExtensionApiModule,
    },
  });
  const imported = await jiti.import<Record<string, unknown>>(descriptor.path);
  const namedFactory = imported.activateAether ?? imported.aether;
  const factory = typeof namedFactory === "function"
    ? namedFactory
    : descriptor.explicit && typeof imported.default === "function"
      ? imported.default
      : undefined;
  return factory as AetherExtensionFactory | undefined;
}

async function cleanupRuntime(
  previous: AetherRuntimeState,
  errorTarget: AetherRuntimeState = previous,
): Promise<void> {
  for (const extension of [...previous.extensions].reverse()) {
    if (!extension.cleanup) continue;
    try {
      await extension.cleanup();
    } catch (error) {
      recordRuntimeError(errorTarget, {
        path: extension.path,
        extension_id: extension.id,
        phase: "load",
        error: `Cleanup failed: ${errorMessage(error)}`,
      });
    }
  }
}

export function configureAetherExtensionTransport(nextTransport: AetherExtensionTransport): void {
  transport = nextTransport;
}

function closeExtensionWatchers(): void {
  for (const watcher of extensionWatchers.splice(0)) {
    watcher.close();
  }
  if (extensionWatchTimer) {
    clearTimeout(extensionWatchTimer);
    extensionWatchTimer = undefined;
  }
}

function configureExtensionWatchers(runtimeState: AetherRuntimeState): void {
  closeExtensionWatchers();
  const watchDirectories = new Set<string>([
    AETHER_EXTENSION_ROOT,
    PI_EXTENSION_ROOT,
    ...runtimeState.extensions.map((extension) => path.dirname(extension.path)),
  ]);
  for (const directory of watchDirectories) {
    if (!fs.existsSync(directory)) continue;
    try {
      const watcher = fs.watch(directory, { recursive: true }, () => {
        if (extensionWatchTimer) clearTimeout(extensionWatchTimer);
        extensionWatchTimer = setTimeout(() => {
          extensionWatchTimer = undefined;
          void loadAetherAppExtensions(runtime.cwd, currentLoadOptions);
        }, 200);
        extensionWatchTimer.unref();
      });
      watcher.unref();
      extensionWatchers.push(watcher);
    } catch {
      // A failed watcher must not prevent extensions from loading.
    }
  }
}

async function loadAetherAppExtensionsUnlocked(
  cwd: string,
  loadOptions: {
    disabledExtensionPaths?: string[];
    disabledPackageSources?: string[];
  } = {},
): Promise<AetherAppExtensionLoadResult> {
  const previous = runtime;
  const candidate = createEmptyRuntime(cwd);
  const descriptors = await discoverAetherExtensionEntries(cwd, loadOptions);
  for (const descriptor of descriptors) {
    const extension: LoadedAetherExtension = { ...descriptor };
    try {
      if (descriptor.compatibilityError) {
        throw new Error(descriptor.compatibilityError);
      }
      const factory = await loadFactory(descriptor);
      if (!factory) continue;
      const cleanup = await factory(createApi(candidate, extension));
      if (typeof cleanup === "function") extension.cleanup = cleanup;
      candidate.extensions.push(extension);
    } catch (error) {
      recordRuntimeError(candidate, {
        path: descriptor.path,
        extension_id: descriptor.id,
        phase: "load",
        error: errorMessage(error),
      });
    }
  }
  if (candidate.errors.length > 0) {
    await cleanupRuntime(candidate);
    for (const error of candidate.errors) recordRuntimeError(previous, error);
    bumpVersion();
    return {
      reloaded: false,
      errors: candidate.errors,
    };
  }
  runtime = candidate;
  currentLoadOptions = cloneJson(loadOptions);
  configureExtensionWatchers(candidate);
  await cleanupRuntime(previous, candidate);
  bumpVersion();
  return {
    reloaded: true,
    errors: [],
  };
}

export async function loadAetherAppExtensions(
  cwd: string,
  loadOptions: {
    disabledExtensionPaths?: string[];
    disabledPackageSources?: string[];
  } = {},
): Promise<AetherAppExtensionLoadResult> {
  return withRuntimeLock(() => loadAetherAppExtensionsUnlocked(cwd, loadOptions));
}

async function renderRegisteredView(
  extension: LoadedAetherExtension,
  render: AetherSurfaceDefinition["render"],
  phasePath: string,
): Promise<AetherView> {
  try {
    const value = typeof render === "function"
      ? await render(createRenderContext(extension))
      : render;
    return cloneJson(value);
  } catch (error) {
    recordRuntimeError(runtime, {
      path: phasePath,
      extension_id: extension.id,
      phase: "render",
      error: errorMessage(error),
    });
    return {
      type: "card",
      tone: "error",
      children: [
        {
          type: "text",
          text: `Extension render failed: ${errorMessage(error)}`,
          color: "error",
        },
      ],
    };
  }
}

async function aetherAppExtensionSnapshotUnlocked(
  hostContext: AetherJsonObject = {},
): Promise<AetherJsonObject> {
  latestHostContext = cloneJson(hostContext);
  const surfaces = [];
  for (const surface of [...runtime.surfaces.values()].sort((left, right) =>
    left.order - right.order || left.id.localeCompare(right.id)
  )) {
    surfaces.push({
      id: surface.id,
      extension_id: surface.extension.id,
      extension_name: surface.extension.name,
      slot: surface.slot,
      order: surface.order,
      tree: await renderRegisteredView(surface.extension, surface.render, surface.id),
    });
  }
  const pages = [];
  for (const page of [...runtime.pages.values()].sort((left, right) =>
    left.order - right.order || left.title.localeCompare(right.title)
  )) {
    pages.push({
      id: page.id,
      local_id: page.localId,
      extension_id: page.extension.id,
      extension_name: page.extension.name,
      title: page.title,
      subtitle: page.subtitle,
      icon: page.icon,
      order: page.order,
      tree: await renderRegisteredView(page.extension, page.render, page.id),
    });
  }
  const components = [];
  for (const component of [...runtime.components.values()].sort((left, right) =>
    left.order - right.order || left.id.localeCompare(right.id)
  )) {
    components.push({
      id: component.id,
      extension_id: component.extension.id,
      extension_name: component.extension.name,
      target: component.target,
      mode: component.mode,
      order: component.order,
      tree: component.mode === "hide"
        ? null
        : await renderRegisteredView(component.extension, component.render, component.id),
    });
  }
  return {
    api_version: AETHER_API_VERSION,
    version: runtimeVersion,
    extensions: runtime.extensions.map((extension) => ({
      id: extension.id,
      name: extension.name,
      path: extension.path,
    })),
    surfaces,
    components,
    pages,
    event_names: [...runtime.events.keys()].sort(),
    errors: runtime.errors,
  };
}

export async function aetherAppExtensionSnapshot(
  hostContext: AetherJsonObject = {},
): Promise<AetherJsonObject> {
  return withRuntimeLock(() => aetherAppExtensionSnapshotUnlocked(hostContext));
}

async function invokeAetherAppExtensionActionUnlocked(
  extensionId: string,
  actionId: string,
  payload: AetherJsonObject,
  hostContext: AetherJsonObject = {},
): Promise<AetherJsonObject> {
  latestHostContext = cloneJson(hostContext);
  const id = actionId.includes(":") ? actionId : scopedId(extensionId, actionId);
  const action = runtime.actions.get(id);
  if (!action) throw new Error(`Unknown Aether extension action: ${actionId}`);
  try {
    const result = await action.handler(
      cloneJson(payload),
      {
        ...createRenderContext(action.extension),
        action: action.localId,
      },
    );
    bumpVersion();
    return {
      invoked: true,
      action: action.localId,
      result: cloneJson(result),
    };
  } catch (error) {
    recordRuntimeError(runtime, {
      path: action.extension.path,
      extension_id: action.extension.id,
      phase: "action",
      error: errorMessage(error),
    });
    bumpVersion();
    throw error;
  }
}

export async function invokeAetherAppExtensionAction(
  extensionId: string,
  actionId: string,
  payload: AetherJsonObject,
  hostContext: AetherJsonObject = {},
): Promise<AetherJsonObject> {
  return withRuntimeLock(() =>
    invokeAetherAppExtensionActionUnlocked(
      extensionId,
      actionId,
      payload,
      hostContext,
    )
  );
}

async function dispatchAetherAppExtensionEventUnlocked(
  eventName: string,
  payload: AetherJsonObject,
  hostContext: AetherJsonObject = {},
): Promise<AetherJsonObject> {
  latestHostContext = cloneJson(hostContext);
  const handlers = runtime.events.get(eventName) ?? [];
  let chainedPayload = cloneJson(payload);
  let cancelled = false;
  let reason = "";
  const results: unknown[] = [];
  for (const registration of handlers) {
    try {
      const rawResult = await registration.handler(
        cloneJson(chainedPayload),
        {
          ...createRenderContext(registration.extension),
          event: eventName,
        },
      );
      results.push(cloneJson(rawResult));
      const result = asObject(rawResult);
      if (result.cancel === true || result.cancelled === true) {
        cancelled = true;
        reason = typeof result.reason === "string" ? result.reason : reason;
      }
      const explicitPayload = asObject(result.payload);
      const patch = Object.keys(explicitPayload).length > 0
        ? explicitPayload
        : Object.fromEntries(
          Object.entries(result).filter(([key]) =>
            !["cancel", "cancelled", "reason", "result"].includes(key)
          ),
        );
      if (Object.keys(patch).length > 0) {
        chainedPayload = { ...chainedPayload, ...cloneJson(patch) };
      }
      if (cancelled) break;
    } catch (error) {
      recordRuntimeError(runtime, {
        path: registration.extension.path,
        extension_id: registration.extension.id,
        phase: "event",
        error: errorMessage(error),
      });
    }
  }
  if (handlers.length > 0) bumpVersion();
  return {
    event: eventName,
    handled: handlers.length > 0,
    cancelled,
    reason,
    payload: chainedPayload,
    results,
  };
}

export async function dispatchAetherAppExtensionEvent(
  eventName: string,
  payload: AetherJsonObject,
  hostContext: AetherJsonObject = {},
): Promise<AetherJsonObject> {
  return withRuntimeLock(() =>
    dispatchAetherAppExtensionEventUnlocked(eventName, payload, hostContext)
  );
}

export function aetherAppExtensionCountForManifest(
  manifest: AetherJsonObject | undefined,
): number {
  const extensions = asObject(manifest?.aether).extensions;
  return Array.isArray(extensions)
    ? extensions.filter((entry) => typeof entry === "string" && entry.trim().length > 0).length
    : 0;
}

function node(type: string, properties: AetherJsonObject = {}): AetherJsonObject {
  return { type, ...properties };
}

export const ui = {
  node,
  text(text: string, properties: AetherJsonObject = {}) {
    return node("text", { text, ...properties });
  },
  code(text: string, properties: AetherJsonObject = {}) {
    return node("code", { text, ...properties });
  },
  column(children: AetherView[], properties: AetherJsonObject = {}) {
    return node("column", { children, ...properties });
  },
  row(children: AetherView[], properties: AetherJsonObject = {}) {
    return node("row", { children, ...properties });
  },
  box(children: AetherView[], properties: AetherJsonObject = {}) {
    return node("box", { children, ...properties });
  },
  card(children: AetherView[], properties: AetherJsonObject = {}) {
    return node("card", { children, ...properties });
  },
  button(label: string, action: string, properties: AetherJsonObject = {}) {
    return node("button", { label, action, ...properties });
  },
  iconButton(icon: string, action: string, properties: AetherJsonObject = {}) {
    return node("iconButton", { icon, action, ...properties });
  },
  switch(label: string, checked: boolean, action: string, properties: AetherJsonObject = {}) {
    return node("switch", { label, checked, action, ...properties });
  },
  input(value: string, action: string, properties: AetherJsonObject = {}) {
    return node("input", { value, action, ...properties });
  },
  spacer(size = 8, properties: AetherJsonObject = {}) {
    return node("spacer", { size, ...properties });
  },
  progress(value?: number, properties: AetherJsonObject = {}) {
    return node("progress", { value, ...properties });
  },
  web(properties: AetherJsonObject) {
    return node("web", properties);
  },
  core(properties: AetherJsonObject = {}) {
    return node("core", properties);
  },
} as const satisfies AetherUi;

export function defineAetherExtension<T extends AetherExtensionFactory>(factory: T): T {
  return factory;
}

export const aetherExtensionApiModule = {
  defineAetherExtension,
  ui,
};
