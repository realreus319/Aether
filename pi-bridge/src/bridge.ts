import { createInterface } from "node:readline";
import { stdin as input, stdout as output, stderr } from "node:process";
import {
  createModels,
  createProvider,
  fauxAssistantMessage,
  fauxProvider,
  fauxToolCall,
  type AssistantMessage,
  type AssistantMessageEvent,
  type Context,
  type ImageContent,
  type Message,
  type Model,
  type MutableModels,
  type ProviderStreams,
  type SimpleStreamOptions,
  type TextContent,
  type Usage,
} from "@earendil-works/pi-ai";
import {
  AgentHarness,
  InMemorySessionRepo,
  NodeExecutionEnv,
  type AgentMessage,
  type AgentTool,
  type AgentToolResult,
} from "@earendil-works/pi-agent-core/node";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import { openAIResponsesApi } from "@earendil-works/pi-ai/api/openai-responses.lazy";
import { anthropicMessagesApi } from "@earendil-works/pi-ai/api/anthropic-messages.lazy";
import { googleVertexApi } from "@earendil-works/pi-ai/api/google-vertex.lazy";
import type { TSchema } from "typebox";

const BRIDGE_VERSION = "2.0.0-alpha.0";
const PI_AI_VERSION = "0.80.3";
const PI_AGENT_CORE_VERSION = "0.80.3";

type JsonObject = Record<string, unknown>;

interface BridgeRequest {
  id?: string;
  type?: string;
  payload?: JsonObject;
}

interface ModelConfig {
  provider_type: string;
  pi_provider_id: string;
  pi_api: string;
  model_id: string;
  base_url: string;
  api_key?: string;
  custom_headers?: Record<string, string>;
  reasoning?: boolean;
  context_window?: number;
  max_tokens?: number;
  faux_response?: string;
  faux_tool_calls?: Array<{ name: string; arguments: JsonObject; id?: string }>;
}

interface HostToolDefinition {
  name: string;
  description: string;
  parameters?: JsonObject;
  execution_mode?: "sequential" | "parallel";
}

interface PendingHostToolRequest {
  resolve: (result: AgentToolResult<JsonObject>) => void;
  reject: (error: Error) => void;
  onUpdate?: (partialResult: AgentToolResult<JsonObject>) => void;
  timeout: NodeJS.Timeout;
}

const activeAborters = new Map<string, () => void | Promise<unknown>>();
const pendingHostToolRequests = new Map<string, PendingHostToolRequest>();
let defaultModelConfig: ModelConfig | undefined;
let hostToolCounter = 0;

function writeFrame(frame: JsonObject): void {
  output.write(`${JSON.stringify(frame)}\n`);
}

function writeEvent(id: string, event: string, payload: JsonObject = {}): void {
  writeFrame({ type: "event", id, event, payload });
}

function writeResponse(id: string, payload: JsonObject = {}): void {
  writeFrame({ type: "response", id, ok: true, payload });
}

function writeError(id: string | undefined, error: unknown, code = "bridge_error"): void {
  const message = error instanceof Error ? error.message : String(error);
  writeFrame({
    type: "error",
    id: id ?? "",
    ok: false,
    error: {
      code,
      message,
    },
  });
}

function asObject(value: unknown): JsonObject {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as JsonObject) : {};
}

function asString(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function asNumber(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function asBoolean(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function normalizeHeaders(value: unknown): Record<string, string> {
  const inputHeaders = asObject(value);
  const headers: Record<string, string> = {};
  for (const [key, rawValue] of Object.entries(inputHeaders)) {
    if (!key.trim()) continue;
    if (typeof rawValue === "string") headers[key] = rawValue;
  }
  return headers;
}

function normalizeModelConfig(rawValue: unknown): ModelConfig {
  const raw = asObject(rawValue);
  const providerType = asString(raw.provider_type).trim();
  const piProviderId = asString(raw.pi_provider_id).trim();
  const piApi = asString(raw.pi_api).trim();
  const modelId = asString(raw.model_id).trim();
  const baseUrl = asString(raw.base_url).trim();
  if (!providerType) throw new Error("model_config.provider_type is required.");
  if (!piProviderId) throw new Error("model_config.pi_provider_id is required.");
  if (!piApi) throw new Error("model_config.pi_api is required.");
  if (!modelId) throw new Error("model_config.model_id is required.");
  if (providerType !== "faux" && !baseUrl) throw new Error("model_config.base_url is required.");
  return {
    provider_type: providerType,
    pi_provider_id: piProviderId,
    pi_api: piApi,
    model_id: modelId,
    base_url: baseUrl,
    api_key: asString(raw.api_key),
    custom_headers: normalizeHeaders(raw.custom_headers),
    reasoning: asBoolean(raw.reasoning, false),
    context_window: asNumber(raw.context_window, 128000),
    max_tokens: asNumber(raw.max_tokens, 16384),
    faux_response: asString(raw.faux_response),
    faux_tool_calls: normalizeFauxToolCalls(raw.faux_tool_calls),
  };
}

function normalizeFauxToolCalls(rawValue: unknown): Array<{ name: string; arguments: JsonObject; id?: string }> {
  if (!Array.isArray(rawValue)) return [];
  return rawValue.flatMap((rawCall) => {
    const raw = asObject(rawCall);
    const name = asString(raw.name, asString(raw.tool_name)).trim();
    if (!name) return [];
    return [
      {
        name,
        arguments: asObject(raw.arguments),
        id: asString(raw.id).trim() || undefined,
      },
    ];
  });
}

function apiStreamsFor(piApi: string): ProviderStreams {
  switch (piApi) {
    case "openai-responses":
      return openAIResponsesApi();
    case "openai-completions":
      return openAICompletionsApi();
    case "anthropic-messages":
      return anthropicMessagesApi();
    case "google-vertex":
      return googleVertexApi();
    default:
      throw new Error(`Unsupported Pi API: ${piApi}`);
  }
}

function createAetherModel(config: ModelConfig): Model<string> {
  return {
    id: config.model_id,
    name: config.model_id,
    api: config.pi_api,
    provider: config.pi_provider_id,
    baseUrl: config.base_url,
    reasoning: config.reasoning ?? false,
    input: ["text", "image"],
    cost: {
      input: 0,
      output: 0,
      cacheRead: 0,
      cacheWrite: 0,
    },
    contextWindow: config.context_window ?? 128000,
    maxTokens: config.max_tokens ?? 16384,
    headers: config.custom_headers,
  };
}

function buildModels(config: ModelConfig): { models: MutableModels; model: Model<string> } {
  const models = createModels();
  if (config.provider_type === "faux") {
    const faux = fauxProvider({
      provider: config.pi_provider_id,
      models: [
        {
          id: config.model_id,
          reasoning: config.reasoning ?? true,
          input: ["text", "image"],
          contextWindow: config.context_window ?? 128000,
          maxTokens: config.max_tokens ?? 16384,
        },
      ],
      tokensPerSecond: 0,
    });
    if (config.faux_tool_calls && config.faux_tool_calls.length > 0) {
      faux.setResponses([
        fauxAssistantMessage(
          config.faux_tool_calls.map((toolCall) =>
            fauxToolCall(toolCall.name, toolCall.arguments, toolCall.id ? { id: toolCall.id } : undefined),
          ),
          { stopReason: "toolUse" },
        ),
        fauxAssistantMessage(config.faux_response || "ok"),
      ]);
    } else {
      faux.setResponses([fauxAssistantMessage(config.faux_response || "ok")]);
    }
    models.setProvider(faux.provider);
    const model = faux.getModel(config.model_id) ?? faux.getModel();
    return { models, model };
  }

  const model = createAetherModel(config);
  const headers = config.custom_headers ?? {};
  const provider = createProvider({
    id: config.pi_provider_id,
    name: config.pi_provider_id,
    baseUrl: config.base_url,
    headers,
    auth: {
      apiKey: {
        name: "Aether provider credentials",
        resolve: async () => ({
          auth: {
            apiKey: config.api_key || undefined,
            baseUrl: config.base_url || undefined,
            headers,
          },
          source: "Aether",
        }),
      },
    },
    models: [model],
    api: apiStreamsFor(config.pi_api),
  });
  models.setProvider(provider);
  return { models, model };
}

function normalizeContentPart(rawValue: unknown): { type: "text"; text: string } | { type: "image"; mimeType: string; data: string } | undefined {
  const raw = asObject(rawValue);
  const type = asString(raw.type);
  if (type === "image") {
    const data = asString(raw.data).trim();
    if (!data) return undefined;
    return {
      type: "image",
      mimeType: asString(raw.mime_type, asString(raw.mimeType, "application/octet-stream")),
      data,
    };
  }
  const text = asString(raw.text);
  return { type: "text", text };
}

function normalizeUserContent(rawContent: unknown): string | Array<{ type: "text"; text: string } | { type: "image"; mimeType: string; data: string }> {
  if (typeof rawContent === "string") return rawContent;
  if (!Array.isArray(rawContent)) return "";
  const parts = rawContent.map(normalizeContentPart).filter((part): part is NonNullable<typeof part> => Boolean(part));
  if (parts.length === 1 && parts[0].type === "text") return parts[0].text;
  return parts;
}

function normalizeMessages(rawMessages: unknown): Context["messages"] {
  if (!Array.isArray(rawMessages)) return [];
  return rawMessages.flatMap((rawMessage): Message[] => {
    const raw = asObject(rawMessage);
    const role = asString(raw.role);
    if (role === "assistant") {
      const providerPayload = asObject(raw.provider_payload);
      if (providerPayload.role === "assistant" && Array.isArray(providerPayload.content)) {
        return [providerPayload as unknown as AssistantMessage];
      }
      return [
        {
          role: "assistant" as const,
          content: [{ type: "text" as const, text: asString(raw.text, asString(raw.content)) }],
          api: asString(raw.api, "aether"),
          provider: asString(raw.provider, "aether"),
          model: asString(raw.model, "unknown"),
          usage: emptyUsage(),
          stopReason: "stop" as const,
          timestamp: asNumber(raw.timestamp, Date.now()),
        },
      ];
    }
    if (role === "toolResult") {
      return [
        {
          role: "toolResult" as const,
          toolCallId: asString(raw.tool_call_id, asString(raw.toolCallId)),
          toolName: asString(raw.tool_name, asString(raw.toolName)),
          content: [{ type: "text" as const, text: asString(raw.text, asString(raw.content)) }],
          isError: asBoolean(raw.is_error, asBoolean(raw.isError, false)),
          timestamp: asNumber(raw.timestamp, Date.now()),
        },
      ];
    }
    return [
      {
        role: "user" as const,
        content: normalizeUserContent(raw.content ?? raw.text),
        timestamp: asNumber(raw.timestamp, Date.now()),
      },
    ];
  });
}

function buildContext(payload: JsonObject): Context {
  return {
    systemPrompt: asString(payload.system_prompt),
    messages: normalizeMessages(payload.messages),
  };
}

function emptyUsage(): Usage {
  return {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
    totalTokens: 0,
    cost: {
      input: 0,
      output: 0,
      cacheRead: 0,
      cacheWrite: 0,
      total: 0,
    },
  };
}

function usagePayload(usage: Usage | undefined): JsonObject {
  if (!usage) return {};
  return {
    input_tokens: usage.input,
    output_tokens: usage.output,
    total_tokens: usage.totalTokens,
    reasoning_tokens: usage.reasoning,
    cached_input_tokens: usage.cacheRead,
  };
}

function assistantText(message: AssistantMessage): string {
  return message.content
    .filter((block) => block.type === "text")
    .map((block) => block.text)
    .join("");
}

function assistantThinking(message: AssistantMessage): string {
  return message.content
    .filter((block) => block.type === "thinking")
    .map((block) => block.thinking)
    .join("");
}

function assistantPayload(message: AssistantMessage): JsonObject {
  return {
    assistant_text: assistantText(message),
    reasoning_text: assistantThinking(message),
    assistant_message: message as unknown as JsonObject,
    usage: usagePayload(message.usage),
    provider: message.provider,
    model: message.model,
    response_id: message.responseId,
    stop_reason: message.stopReason,
    error_message: message.errorMessage,
  };
}

function emitStreamEvent(requestId: string, event: AssistantMessageEvent): void {
  switch (event.type) {
    case "text_delta":
      writeEvent(requestId, "assistant_text_delta", { delta: event.delta });
      break;
    case "thinking_delta":
      writeEvent(requestId, "assistant_reasoning_delta", { delta: event.delta });
      break;
    case "toolcall_start":
      writeEvent(requestId, "tool_call_start", { content_index: event.contentIndex });
      break;
    case "toolcall_delta":
      writeEvent(requestId, "tool_call_delta", { content_index: event.contentIndex, delta: event.delta });
      break;
    case "toolcall_end":
      writeEvent(requestId, "tool_call_end", {
        id: event.toolCall.id,
        name: event.toolCall.name,
        arguments: event.toolCall.arguments,
      });
      break;
    case "done":
      writeEvent(requestId, "assistant_done", assistantPayload(event.message));
      break;
    case "error":
      writeEvent(requestId, "assistant_error", assistantPayload(event.error));
      break;
  }
}

function streamOptionsFor(payload: JsonObject, signal: AbortSignal): SimpleStreamOptions {
  const options: SimpleStreamOptions = {
    signal,
    sessionId: asString(payload.session_id),
    headers: normalizeHeaders(payload.headers),
  };
  const temperature = payload.temperature;
  if (typeof temperature === "number") options.temperature = temperature;
  const maxTokens = payload.max_tokens;
  if (typeof maxTokens === "number") options.maxTokens = maxTokens;
  const reasoning = asString(payload.reasoning);
  if (reasoning && reasoning !== "off") {
    options.reasoning = reasoning as SimpleStreamOptions["reasoning"];
  }
  return options;
}

function normalizeHostToolDefinitions(rawTools: unknown): HostToolDefinition[] {
  if (!Array.isArray(rawTools)) return [];
  return rawTools
    .map((rawTool): HostToolDefinition | undefined => {
      const raw = asObject(rawTool);
      const name = asString(raw.name).trim();
      if (!name) return undefined;
      const executionMode = asString(raw.execution_mode, asString(raw.executionMode)).trim();
      return {
        name,
        description: asString(raw.description),
        parameters: asObject(raw.parameters),
        execution_mode: executionMode === "sequential" ? "sequential" : "parallel",
      };
    })
    .filter((tool): tool is HostToolDefinition => Boolean(tool));
}

function hostToolSchema(definition: HostToolDefinition): TSchema {
  const schema = asObject(definition.parameters);
  if (asString(schema.type)) return schema as unknown as TSchema;
  return {
    type: "object",
    properties: {},
    additionalProperties: true,
  } as unknown as TSchema;
}

function normalizeToolArguments(args: unknown): JsonObject {
  if (typeof args === "string") {
    try {
      return asObject(JSON.parse(args));
    } catch {
      return {};
    }
  }
  return asObject(args);
}

function normalizeToolContent(rawContent: unknown, fallbackText: string): Array<TextContent | ImageContent> {
  if (!Array.isArray(rawContent)) return [{ type: "text", text: fallbackText }];
  const content = rawContent
    .map((rawPart): TextContent | ImageContent | undefined => {
      const part = asObject(rawPart);
      const type = asString(part.type);
      if (type === "image") {
        const data = asString(part.data).trim();
        if (!data) return undefined;
        return {
          type: "image",
          mimeType: asString(part.mime_type, asString(part.mimeType, "application/octet-stream")),
          data,
        };
      }
      if (type === "text") {
        return { type: "text", text: asString(part.text) };
      }
      return undefined;
    })
    .filter((part): part is TextContent | ImageContent => Boolean(part));
  return content.length > 0 ? content : [{ type: "text", text: fallbackText }];
}

function hostToolResultFromPayload(payload: JsonObject): AgentToolResult<JsonObject> {
  const outputText = asString(payload.output_json, asString(payload.output, ""));
  const details = {
    ...asObject(payload.details),
    tool_request_id: asString(payload.tool_request_id),
    tool_call_id: asString(payload.tool_call_id),
    tool_name: asString(payload.tool_name),
    arguments_json: asString(payload.arguments_json),
    output_json: outputText,
    raw_output_json: asString(payload.raw_output_json),
    is_error: asBoolean(payload.is_error, false),
  };
  return {
    content: normalizeToolContent(payload.content, outputText),
    details,
    terminate: asBoolean(payload.terminate, false) || undefined,
  };
}

function resolveHostToolResult(payload: JsonObject): boolean {
  const toolRequestId = asString(payload.tool_request_id).trim();
  const pending = toolRequestId ? pendingHostToolRequests.get(toolRequestId) : undefined;
  if (!pending) return false;
  pendingHostToolRequests.delete(toolRequestId);
  clearTimeout(pending.timeout);
  pending.resolve(hostToolResultFromPayload(payload));
  return true;
}

function applyHostToolProgress(payload: JsonObject): boolean {
  const toolRequestId = asString(payload.tool_request_id).trim();
  const pending = toolRequestId ? pendingHostToolRequests.get(toolRequestId) : undefined;
  if (!pending) return false;
  pending.onUpdate?.(hostToolResultFromPayload(payload));
  return true;
}

function requestHostTool(
  runRequestId: string,
  sessionId: string,
  toolName: string,
  toolCallId: string,
  params: JsonObject,
  signal?: AbortSignal,
  onUpdate?: (partialResult: AgentToolResult<JsonObject>) => void,
): Promise<AgentToolResult<JsonObject>> {
  const toolRequestId = `host-tool-${Date.now()}-${++hostToolCounter}`;
  const argumentsJson = JSON.stringify(params);
  writeEvent(runRequestId, "host_tool_request", {
    tool_request_id: toolRequestId,
    session_id: sessionId,
    tool_call_id: toolCallId,
    tool_name: toolName,
    arguments: params,
    arguments_json: argumentsJson,
  });
  return new Promise<AgentToolResult<JsonObject>>((resolve, reject) => {
    if (signal?.aborted) {
      reject(new Error("Host tool execution aborted."));
      return;
    }
    const timeout = setTimeout(() => {
      pendingHostToolRequests.delete(toolRequestId);
      reject(new Error(`Host tool ${toolName} timed out waiting for Kotlin result.`));
    }, 10 * 60 * 1000);
    const abortListener = () => {
      clearTimeout(timeout);
      pendingHostToolRequests.delete(toolRequestId);
      reject(new Error("Host tool execution aborted."));
    };
    signal?.addEventListener("abort", abortListener, { once: true });
    pendingHostToolRequests.set(toolRequestId, {
      resolve: (result) => {
        signal?.removeEventListener("abort", abortListener);
        resolve(result);
      },
      reject: (error) => {
        signal?.removeEventListener("abort", abortListener);
        reject(error);
      },
      onUpdate,
      timeout,
    });
  });
}

function createHostTool(runRequestId: string, sessionId: string, definition: HostToolDefinition): AgentTool<TSchema, JsonObject> {
  return {
    label: definition.name,
    name: definition.name,
    description: definition.description,
    parameters: hostToolSchema(definition),
    prepareArguments: normalizeToolArguments,
    executionMode: definition.execution_mode,
    execute: async (toolCallId, params, signal, onUpdate) =>
      requestHostTool(runRequestId, sessionId, definition.name, toolCallId, normalizeToolArguments(params), signal, onUpdate),
  };
}

function toolTextOutput(result: AgentToolResult<JsonObject> | undefined): string {
  if (!result) return "";
  return result.content
    .filter((part): part is TextContent => part.type === "text")
    .map((part) => part.text)
    .join("");
}

function toolEventPayload(
  toolCallId: string,
  toolName: string,
  args: unknown,
  result?: AgentToolResult<JsonObject>,
  isError?: boolean,
): JsonObject {
  const argsObject = normalizeToolArguments(args);
  const details = asObject(result?.details);
  return {
    id: toolCallId,
    name: toolName,
    arguments: argsObject,
    arguments_json: JSON.stringify(argsObject),
    output_json: asString(details.output_json, toolTextOutput(result)),
    raw_output_json: asString(details.raw_output_json),
    content: result?.content ?? [],
    details,
    is_error: isError ?? asBoolean(details.is_error, false),
  };
}

function promptFromLastUserMessage(messages: Message[]): {
  history: AgentMessage[];
  text: string;
  images: ImageContent[];
} {
  const last = messages[messages.length - 1];
  if (!last || last.role !== "user") {
    return { history: messages as AgentMessage[], text: "", images: [] };
  }
  const content = last.content;
  if (typeof content === "string") {
    return { history: messages.slice(0, -1) as AgentMessage[], text: content, images: [] };
  }
  const text = content
    .filter((part): part is TextContent => part.type === "text")
    .map((part) => part.text)
    .join("\n");
  const images = content.filter((part): part is ImageContent => part.type === "image");
  return { history: messages.slice(0, -1) as AgentMessage[], text, images };
}

function thinkingLevelFor(payload: JsonObject): "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | undefined {
  const reasoning = asString(payload.reasoning).trim();
  if (!reasoning) return undefined;
  if (["off", "minimal", "low", "medium", "high", "xhigh"].includes(reasoning)) {
    return reasoning as "off" | "minimal" | "low" | "medium" | "high" | "xhigh";
  }
  return undefined;
}

function emitHarnessEvent(
  requestId: string,
  event: Parameters<AgentHarness["subscribe"]>[0] extends (event: infer TEvent, signal?: AbortSignal) => unknown ? TEvent : never,
  toolArgsById: Map<string, unknown>,
): void {
  switch (event.type) {
    case "message_update":
      if (event.message.role === "assistant") {
        if (event.assistantMessageEvent.type === "text_delta" || event.assistantMessageEvent.type === "thinking_delta") {
          emitStreamEvent(requestId, event.assistantMessageEvent);
        }
      }
      break;
    case "tool_execution_start":
      toolArgsById.set(event.toolCallId, event.args);
      writeEvent(requestId, "tool_call_start", toolEventPayload(event.toolCallId, event.toolName, event.args));
      break;
    case "tool_execution_update":
      writeEvent(requestId, "tool_call_delta", toolEventPayload(event.toolCallId, event.toolName, event.args, event.partialResult));
      break;
    case "tool_execution_end":
      writeEvent(
        requestId,
        "tool_call_end",
        toolEventPayload(event.toolCallId, event.toolName, toolArgsById.get(event.toolCallId) ?? {}, event.result, event.isError),
      );
      toolArgsById.delete(event.toolCallId);
      break;
  }
}

async function runHarnessTurn(id: string, payload: JsonObject): Promise<JsonObject> {
  const config = normalizeModelConfig(payload.model_config ?? defaultModelConfig);
  const { models, model } = buildModels(config);
  const sessionId = asString(payload.session_id, id);
  const messages = normalizeMessages(payload.messages);
  const prompt = promptFromLastUserMessage(messages);
  const sessionRepo = new InMemorySessionRepo();
  const session = await sessionRepo.create({ id: sessionId });
  for (const message of prompt.history) {
    await session.appendMessage(message);
  }
  const tools = normalizeHostToolDefinitions(payload.host_tools).map((tool) => createHostTool(id, sessionId, tool));
  const harness = new AgentHarness({
    models,
    env: new NodeExecutionEnv({ cwd: asString(payload.workspace_directory, process.cwd()) || process.cwd() }),
    session,
    model,
    systemPrompt: asString(payload.system_prompt),
    tools,
    thinkingLevel: thinkingLevelFor(payload),
    streamOptions: {
      headers: normalizeHeaders(payload.headers),
    },
  });
  const toolArgsById = new Map<string, unknown>();
  harness.subscribe((event) => emitHarnessEvent(id, event, toolArgsById));
  harness.on("tool_result", (event) => {
    const details = asObject(event.details);
    if (asBoolean(details.is_error, false)) return { isError: true };
    return undefined;
  });
  activeAborters.set(id, () => harness.abort());
  try {
    const message = await harness.prompt(prompt.text, prompt.images.length > 0 ? { images: prompt.images } : undefined);
    await harness.waitForIdle();
    return assistantPayload(message);
  } finally {
    activeAborters.delete(id);
  }
}

async function runSimpleCompletion(id: string, payload: JsonObject, stream: boolean): Promise<JsonObject> {
  const config = normalizeModelConfig(payload.model_config ?? defaultModelConfig);
  const { models, model } = buildModels(config);
  const controller = new AbortController();
  activeAborters.set(id, () => controller.abort());
  try {
    const context = buildContext(payload);
    const options = streamOptionsFor(payload, controller.signal);
    if (stream) {
      const eventStream = models.streamSimple(model, context, options);
      for await (const event of eventStream) {
        emitStreamEvent(id, event);
      }
      const message = await eventStream.result();
      return assistantPayload(message);
    }
    const message = await models.completeSimple(model, context, options);
    return assistantPayload(message);
  } finally {
    activeAborters.delete(id);
  }
}

async function handleRequest(request: BridgeRequest): Promise<void> {
  const id = asString(request.id);
  const type = asString(request.type);
  const payload = asObject(request.payload);
  if (!id) throw new Error("Request id is required.");
  switch (type) {
    case "ping":
      writeResponse(id, {
        bridge_version: BRIDGE_VERSION,
        pi_ai_version: PI_AI_VERSION,
        pi_agent_core_version: PI_AGENT_CORE_VERSION,
        node_version: process.version,
      });
      return;
    case "set_model_config":
      defaultModelConfig = normalizeModelConfig(payload.model_config);
      writeResponse(id, { configured: true });
      return;
    case "complete_once":
      writeResponse(id, await runSimpleCompletion(id, payload, asBoolean(payload.stream, false)));
      return;
    case "run_turn":
      writeResponse(id, await runHarnessTurn(id, payload));
      return;
    case "start_session":
    case "steer":
    case "follow_up":
      writeResponse(id, { accepted: true });
      return;
    case "host_tool_result":
      writeResponse(id, { accepted: resolveHostToolResult(payload) });
      return;
    case "host_tool_progress":
      writeResponse(id, { accepted: applyHostToolProgress(payload) });
      return;
    case "abort": {
      const targetId = asString(payload.request_id, asString(payload.target_id));
      const aborter = targetId ? activeAborters.get(targetId) : undefined;
      await aborter?.();
      writeResponse(id, { aborted: Boolean(aborter) });
      return;
    }
    default:
      throw new Error(`Unsupported request type: ${type}`);
  }
}

async function main(): Promise<void> {
  if (process.argv.includes("--ping")) {
    writeFrame({
      type: "response",
      id: "ping",
      ok: true,
      payload: {
        bridge_version: BRIDGE_VERSION,
        pi_ai_version: PI_AI_VERSION,
        pi_agent_core_version: PI_AGENT_CORE_VERSION,
        node_version: process.version,
      },
    });
    return;
  }

  const reader = createInterface({ input, crlfDelay: Infinity });
  for await (const line of reader) {
    if (!line.trim()) continue;
    let request: BridgeRequest;
    try {
      request = JSON.parse(line) as BridgeRequest;
    } catch (error) {
      writeError(undefined, error, "invalid_json");
      continue;
    }
    handleRequest(request).catch((error) => {
      writeError(request.id, error);
    });
  }
}

main().catch((error) => {
  stderr.write(`pi-bridge fatal: ${error instanceof Error ? error.stack ?? error.message : String(error)}\n`);
  process.exitCode = 1;
});
