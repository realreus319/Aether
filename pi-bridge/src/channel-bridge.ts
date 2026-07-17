import { createInterface } from "node:readline";
import { stdin as input, stdout as output, stderr } from "node:process";
import { readFile } from "node:fs/promises";
import { randomBytes, randomUUID } from "node:crypto";

const CONFIG_PATH = process.env.AETHER_CHANNEL_CONFIG ?? "/root/.aether/channels.json";
const WECHAT_BASE_URL = "https://ilinkai.weixin.qq.com";
const WECHAT_CHANNEL_VERSION = "2.0.1";

type JsonObject = Record<string, unknown>;
type Account = {
  account_id: string;
  type: "feishu" | "dingtalk" | "wechat";
  display_name?: string;
  enabled?: boolean;
  credentials?: Record<string, string>;
  options?: Record<string, unknown>;
};
type RunningAdapter = { stop(): Promise<void>; send(address: JsonObject, text: string): Promise<void> };

const adapters = new Map<string, RunningAdapter>();
let subscriberId = "";

function frame(value: JsonObject): void { output.write(`${JSON.stringify(value)}\n`); }
function event(name: string, payload: JsonObject): void {
  if (subscriberId) frame({ type: "event", id: subscriberId, event: name, payload });
}
function response(id: string, payload: JsonObject = {}): void { frame({ type: "response", id, ok: true, payload }); }
function error(id: string, cause: unknown): void {
  frame({ type: "error", id, ok: false, error: { code: "channel_bridge_error", message: cause instanceof Error ? cause.message : String(cause) } });
}
function log(...values: unknown[]): void { stderr.write(`[channel-bridge] ${values.map(String).join(" ")}\n`); }

// Third-party SDKs log through console; stdout is reserved for JSONL protocol frames.
console.log = log;
console.info = log;
console.warn = log;
console.error = log;
console.debug = log;

function status(account: Account, state: string, detail = ""): void {
  event("channel_status", {
    account_id: account.account_id,
    channel_type: account.type,
    status: state,
    detail,
    updated_at_millis: Date.now(),
  });
}

function incoming(account: Account, payload: JsonObject): void {
  event("channel_message", {
    account_id: account.account_id,
    channel_type: account.type,
    account_display_name: account.display_name ?? account.type,
    share_session_in_group: Boolean(account.options?.share_session_in_group),
    ...payload,
  });
}

async function loadAccounts(payload: JsonObject): Promise<Account[]> {
  if (Array.isArray(payload.accounts)) return payload.accounts as Account[];
  try {
    const parsed = JSON.parse(await readFile(CONFIG_PATH, "utf8")) as JsonObject;
    return Array.isArray(parsed.accounts) ? parsed.accounts as Account[] : [];
  } catch (cause) {
    log(`config unavailable at ${CONFIG_PATH}:`, cause instanceof Error ? cause.message : cause);
    return [];
  }
}

async function stopAll(): Promise<void> {
  const running = [...adapters.values()];
  adapters.clear();
  await Promise.allSettled(running.map((adapter) => adapter.stop()));
}

async function configure(payload: JsonObject): Promise<JsonObject> {
  await stopAll();
  const accounts = (await loadAccounts(payload)).filter((account) => account.enabled !== false);
  for (const account of accounts) {
    try {
      status(account, "connecting");
      const adapter = account.type === "feishu"
        ? await startFeishu(account)
        : account.type === "dingtalk"
          ? await startDingTalk(account)
          : await startWeChat(account);
      adapters.set(account.account_id, adapter);
      status(account, "connected");
    } catch (cause) {
      const detail = cause instanceof Error ? cause.message : String(cause);
      status(account, "error", detail);
      log(`${account.type}/${account.account_id} failed:`, detail);
    }
  }
  return { configured: accounts.length, config_path: CONFIG_PATH };
}

async function startFeishu(account: Account): Promise<RunningAdapter> {
  const credentials = account.credentials ?? {};
  const appId = credentials.app_id ?? "";
  const appSecret = credentials.app_secret ?? "";
  if (!appId || !appSecret) throw new Error("Feishu app_id and app_secret are required.");
  const packageName = "@larksuiteoapi/node-sdk";
  const sdk = await import(packageName) as any;
  const channel = sdk.createLarkChannel({
    appId,
    appSecret,
    domain: account.options?.domain === "lark" ? "lark" : "feishu",
    policy: {
      requireMention: account.options?.require_mention !== false,
      dmMode: account.options?.dm_disabled === true ? "disabled" : "open",
    },
  });
  channel.on("message", async (message: any) => {
    incoming(account, {
      message_id: String(message.messageId ?? ""),
      sender_id: String(message.senderId ?? ""),
      sender_name: String(message.senderName ?? ""),
      conversation_id: String(message.chatId ?? message.senderId ?? ""),
      is_group: message.chatType === "group",
      bot_mentioned: Boolean(message.mentionedBot),
      text: String(message.content ?? ""),
      reply_address: {
        kind: message.chatType === "group" ? "group" : "dm",
        id: String(message.chatId ?? ""),
        extra: {
          reply_to: String(message.messageId ?? ""),
          thread_id: String(message.threadId ?? message.rootId ?? ""),
        },
      },
      metadata: { raw_content_type: String(message.rawContentType ?? "") },
    });
  });
  channel.on("reconnecting", () => status(account, "reconnecting"));
  channel.on("reconnected", () => status(account, "connected"));
  channel.on("error", (cause: unknown) => status(account, "error", cause instanceof Error ? cause.message : String(cause)));
  await channel.connect();
  return {
    async stop() { await channel.disconnect(); },
    async send(address, text) {
      const extra = (address.extra && typeof address.extra === "object" ? address.extra : {}) as Record<string, string>;
      await channel.send(String(address.id ?? ""), { markdown: text }, {
        replyTo: extra.reply_to || undefined,
        replyInThread: Boolean(extra.thread_id),
      });
    },
  };
}

async function startDingTalk(account: Account): Promise<RunningAdapter> {
  const credentials = account.credentials ?? {};
  const clientId = credentials.client_id ?? "";
  const clientSecret = credentials.client_secret ?? "";
  if (!clientId || !clientSecret) throw new Error("DingTalk client_id and client_secret are required.");
  const packageName = "dingtalk-stream";
  const sdk = await import(packageName) as any;
  const client = new sdk.DWClient({ clientId, clientSecret, keepAlive: true, debug: false });
  client.registerCallbackListener(sdk.TOPIC_ROBOT, async (downstream: any) => {
    // ACK immediately; Agent processing and the real reply happen asynchronously.
    client.socketCallBackResponse(String(downstream.headers?.messageId ?? ""), { status: "SUCCESS" });
    const message = JSON.parse(String(downstream.data ?? "{}")) as JsonObject;
    const text = ((message.text as JsonObject | undefined)?.content ?? "") as string;
    const senderId = String(message.senderStaffId ?? message.senderId ?? "");
    const conversationId = String(message.conversationId ?? senderId);
    const conversationType = String(message.conversationType ?? "1");
    incoming(account, {
      message_id: String(message.msgId ?? downstream.headers?.messageId ?? ""),
      sender_id: senderId,
      sender_name: String(message.senderNick ?? ""),
      conversation_id: conversationId,
      is_group: conversationType !== "1",
      bot_mentioned: true,
      text,
      reply_address: {
        kind: "webhook",
        id: String(message.sessionWebhook ?? ""),
        extra: { sender_staff_id: senderId, conversation_id: conversationId },
      },
      metadata: { conversation_type: conversationType },
    });
  });
  await client.connect();
  return {
    async stop() { client.disconnect(); },
    async send(address, text) {
      const webhook = String(address.id ?? "");
      if (!webhook) throw new Error("DingTalk sessionWebhook is unavailable.");
      const token = await client.getAccessToken();
      const result = await fetch(webhook, {
        method: "POST",
        headers: { "content-type": "application/json", "x-acs-dingtalk-access-token": token },
        body: JSON.stringify({ msgtype: "markdown", markdown: { title: "Aether", text } }),
      });
      if (!result.ok) throw new Error(`DingTalk send failed: HTTP ${result.status}`);
    },
  };
}

function wechatHeaders(token: string): Record<string, string> {
  return {
    "content-type": "application/json",
    AuthorizationType: "ilink_bot_token",
    Authorization: `Bearer ${token}`,
    "X-WECHAT-UIN": randomBytes(4).readUInt32BE(0).toString().replace(/^/, ""),
  };
}

async function startWeChat(account: Account): Promise<RunningAdapter> {
  const credentials = account.credentials ?? {};
  const token = credentials.bot_token ?? "";
  if (!token) throw new Error("WeChat bot_token is required.");
  const baseUrl = String(account.options?.base_url ?? WECHAT_BASE_URL).replace(/\/$/, "");
  let cursor = "";
  let stopped = false;
  const contexts = new Map<string, string>();
  const poll = async () => {
    let failures = 0;
    while (!stopped) {
      try {
        const result = await fetch(`${baseUrl}/ilink/bot/getupdates`, {
          method: "POST",
          headers: wechatHeaders(token),
          body: JSON.stringify({ get_updates_buf: cursor, base_info: { channel_version: WECHAT_CHANNEL_VERSION } }),
        });
        if (!result.ok) throw new Error(`HTTP ${result.status}`);
        const data = await result.json() as JsonObject;
        cursor = String(data.get_updates_buf ?? cursor);
        for (const raw of (Array.isArray(data.msgs) ? data.msgs : []) as JsonObject[]) {
          if (Number(raw.message_type ?? 0) !== 1) continue;
          const senderId = String(raw.from_user_id ?? "");
          const contextToken = String(raw.context_token ?? "");
          const groupId = String(raw.group_id ?? "");
          const items = Array.isArray(raw.item_list) ? raw.item_list as JsonObject[] : [];
          const text = items.filter((item) => Number(item.type) === 1)
            .map((item) => String((item.text_item as JsonObject | undefined)?.text ?? ""))
            .filter(Boolean).join("\n");
          if (!senderId || !text) continue;
          contexts.set(senderId, contextToken);
          incoming(account, {
            message_id: String(raw.msg_id ?? contextToken),
            sender_id: senderId,
            conversation_id: groupId || senderId,
            is_group: Boolean(groupId),
            bot_mentioned: true,
            text,
            reply_address: { kind: groupId ? "group" : "dm", id: senderId, extra: { context_token: contextToken, group_id: groupId } },
            metadata: { context_token: contextToken },
          });
        }
        failures = 0;
      } catch (cause) {
        failures += 1;
        status(account, "reconnecting", cause instanceof Error ? cause.message : String(cause));
        await new Promise((resolve) => setTimeout(resolve, Math.min(120_000, 2 ** failures * 1_000)));
      }
    }
  };
  void poll();
  return {
    async stop() { stopped = true; },
    async send(address, text) {
      const userId = String(address.id ?? "");
      const extra = (address.extra && typeof address.extra === "object" ? address.extra : {}) as Record<string, string>;
      const contextToken = extra.context_token || contexts.get(userId) || "";
      if (!contextToken) throw new Error("WeChat context_token is unavailable.");
      const result = await fetch(`${baseUrl}/ilink/bot/sendmessage`, {
        method: "POST",
        headers: wechatHeaders(token),
        body: JSON.stringify({
          msg: {
            from_user_id: "",
            to_user_id: userId,
            client_id: randomUUID(),
            message_type: 2,
            message_state: 2,
            context_token: contextToken,
            item_list: [{ type: 1, text_item: { text } }],
          },
          base_info: { channel_version: WECHAT_CHANNEL_VERSION },
        }),
      });
      if (!result.ok) throw new Error(`WeChat send failed: HTTP ${result.status}`);
    },
  };
}

async function handle(request: JsonObject): Promise<void> {
  const id = String(request.id ?? "");
  const type = String(request.type ?? "");
  const payload = request.payload && typeof request.payload === "object" ? request.payload as JsonObject : {};
  if (!id) throw new Error("Request id is required.");
  if (type === "subscribe") {
    subscriberId = id;
    event("channel_ready", await configure(payload));
    return;
  }
  if (type === "reload") { response(id, await configure(payload)); return; }
  if (type === "send") {
    const accountId = String(payload.account_id ?? "");
    const adapter = adapters.get(accountId);
    if (!adapter) throw new Error(`Channel account is not connected: ${accountId}`);
    await adapter.send((payload.address ?? {}) as JsonObject, String(payload.text ?? ""));
    response(id, { sent: true });
    return;
  }
  if (type === "stop") { await stopAll(); response(id, { stopped: true }); return; }
  throw new Error(`Unsupported request type: ${type}`);
}

const reader = createInterface({ input, crlfDelay: Infinity });
for await (const line of reader) {
  if (!line.trim()) continue;
  let request: JsonObject;
  try { request = JSON.parse(line) as JsonObject; }
  catch (cause) { error("", cause); continue; }
  handle(request).catch((cause) => error(String(request.id ?? ""), cause));
}
await stopAll();
