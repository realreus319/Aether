import { createInterface } from "node:readline";
import { stdin, stdout, stderr } from "node:process";
import { readFile } from "node:fs/promises";
import { randomBytes, randomUUID } from "node:crypto";
import { createLarkChannel } from "@larksuiteoapi/node-sdk";
import { DWClient, TOPIC_ROBOT } from "dingtalk-stream";

type Obj = Record<string, unknown>;
type Account = { account_id: string; type: "feishu" | "dingtalk" | "wechat"; display_name?: string; enabled?: boolean; credentials?: Record<string, string>; options?: Obj };
type Adapter = { stop(): Promise<void>; send(address: Obj, text: string): Promise<void> };

const configPath = process.env.AETHER_CHANNEL_CONFIG ?? "/root/.aether/channels.json";
const adapters = new Map<string, Adapter>();
let subscriber = "";

const log = (...args: unknown[]) => stderr.write(`[channel] ${args.map(String).join(" ")}\n`);
console.log = log; console.info = log; console.warn = log; console.error = log; console.debug = log;
const write = (value: Obj) => stdout.write(`${JSON.stringify(value)}\n`);
const emit = (name: string, payload: Obj) => subscriber && write({ type: "event", id: subscriber, event: name, payload });
const reply = (id: string, payload: Obj = {}) => write({ type: "response", id, ok: true, payload });
const fail = (id: string, cause: unknown) => write({ type: "error", id, ok: false, error: { code: "channel_error", message: cause instanceof Error ? cause.message : String(cause) } });
const status = (a: Account, state: string, detail = "") => emit("channel_status", { account_id: a.account_id, channel_type: a.type, status: state, detail, updated_at_millis: Date.now() });
const inbound = (a: Account, payload: Obj) => emit("channel_message", { account_id: a.account_id, channel_type: a.type, account_display_name: a.display_name ?? a.type, share_session_in_group: Boolean(a.options?.share_session_in_group), ...payload });

async function accounts(payload: Obj): Promise<Account[]> {
  if (Array.isArray(payload.accounts)) return payload.accounts as Account[];
  try {
    const parsed = JSON.parse(await readFile(configPath, "utf8")) as Obj;
    return Array.isArray(parsed.accounts) ? parsed.accounts as Account[] : [];
  } catch { return []; }
}

async function stopAll() {
  const current = [...adapters.values()]; adapters.clear();
  await Promise.allSettled(current.map((it) => it.stop()));
}

async function startFeishu(a: Account): Promise<Adapter> {
  const c = a.credentials ?? {}; if (!c.app_id || !c.app_secret) throw new Error("Feishu app_id/app_secret required");
  const channel: any = createLarkChannel({ appId: c.app_id, appSecret: c.app_secret, domain: a.options?.domain === "lark" ? "lark" : "feishu", policy: { requireMention: a.options?.require_mention !== false, dmMode: a.options?.dm_disabled === true ? "disabled" : "open" } });
  channel.on("message", (m: any) => inbound(a, {
    message_id: String(m.messageId ?? ""), sender_id: String(m.senderId ?? ""), sender_name: String(m.senderName ?? ""),
    conversation_id: String(m.chatId ?? m.senderId ?? ""), is_group: m.chatType === "group", bot_mentioned: Boolean(m.mentionedBot), text: String(m.content ?? ""),
    reply_address: { kind: m.chatType === "group" ? "group" : "dm", id: String(m.chatId ?? ""), extra: { reply_to: String(m.messageId ?? ""), thread_id: String(m.threadId ?? m.rootId ?? "") } },
    metadata: { raw_content_type: String(m.rawContentType ?? "") },
  }));
  channel.on("reconnecting", () => status(a, "reconnecting")); channel.on("reconnected", () => status(a, "connected"));
  channel.on("error", (e: unknown) => status(a, "error", e instanceof Error ? e.message : String(e)));
  await channel.connect();
  return { stop: () => channel.disconnect(), send: async (address, text) => {
    const extra = (typeof address.extra === "object" && address.extra ? address.extra : {}) as Record<string, string>;
    await channel.send(String(address.id ?? ""), { markdown: text }, { replyTo: extra.reply_to || undefined, replyInThread: Boolean(extra.thread_id) });
  } };
}

async function startDingTalk(a: Account): Promise<Adapter> {
  const c = a.credentials ?? {}; if (!c.client_id || !c.client_secret) throw new Error("DingTalk client_id/client_secret required");
  const client: any = new DWClient({ clientId: c.client_id, clientSecret: c.client_secret, keepAlive: true, debug: false });
  client.registerCallbackListener(TOPIC_ROBOT, (down: any) => {
    client.socketCallBackResponse(String(down.headers?.messageId ?? ""), { status: "SUCCESS" });
    const m = JSON.parse(String(down.data ?? "{}")) as Obj;
    const sender = String(m.senderStaffId ?? m.senderId ?? ""); const conversation = String(m.conversationId ?? sender); const kind = String(m.conversationType ?? "1");
    inbound(a, { message_id: String(m.msgId ?? down.headers?.messageId ?? ""), sender_id: sender, sender_name: String(m.senderNick ?? ""), conversation_id: conversation,
      is_group: kind !== "1", bot_mentioned: true, text: String((m.text as Obj | undefined)?.content ?? ""),
      reply_address: { kind: "webhook", id: String(m.sessionWebhook ?? ""), extra: { sender_staff_id: sender, conversation_id: conversation } }, metadata: { conversation_type: kind } });
  });
  await client.connect();
  return { stop: async () => client.disconnect(), send: async (address, text) => {
    const webhook = String(address.id ?? ""); if (!webhook) throw new Error("DingTalk sessionWebhook unavailable");
    const token = await client.getAccessToken(); const res = await fetch(webhook, { method: "POST", headers: { "content-type": "application/json", "x-acs-dingtalk-access-token": token }, body: JSON.stringify({ msgtype: "markdown", markdown: { title: "Aether", text } }) });
    if (!res.ok) throw new Error(`DingTalk HTTP ${res.status}`);
  } };
}

function wxHeaders(token: string) {
  return { "content-type": "application/json", AuthorizationType: "ilink_bot_token", Authorization: `Bearer ${token}`, "X-WECHAT-UIN": randomBytes(4).toString("base64") };
}
async function startWeChat(a: Account): Promise<Adapter> {
  const c = a.credentials ?? {}; const token = c.bot_token ?? ""; if (!token) throw new Error("WeChat bot_token required");
  const base = String(a.options?.base_url ?? "https://ilinkai.weixin.qq.com").replace(/\/$/, ""); let cursor = ""; let stopped = false; const contexts = new Map<string, string>();
  void (async () => { let failures = 0; while (!stopped) { try {
    const res = await fetch(`${base}/ilink/bot/getupdates`, { method: "POST", headers: wxHeaders(token), body: JSON.stringify({ get_updates_buf: cursor, base_info: { channel_version: "2.0.1" } }) });
    if (!res.ok) throw new Error(`WeChat HTTP ${res.status}`); const data = await res.json() as Obj; cursor = String(data.get_updates_buf ?? cursor);
    for (const m of (Array.isArray(data.msgs) ? data.msgs : []) as Obj[]) { if (Number(m.message_type ?? 0) !== 1) continue;
      const sender = String(m.from_user_id ?? ""), context = String(m.context_token ?? ""), group = String(m.group_id ?? "");
      const text = (Array.isArray(m.item_list) ? m.item_list as Obj[] : []).filter((i) => Number(i.type) === 1).map((i) => String((i.text_item as Obj | undefined)?.text ?? "")).filter(Boolean).join("\n");
      if (!sender || !text) continue; contexts.set(sender, context); inbound(a, { message_id: String(m.msg_id ?? context), sender_id: sender, conversation_id: group || sender, is_group: Boolean(group), bot_mentioned: true, text,
        reply_address: { kind: group ? "group" : "dm", id: sender, extra: { context_token: context, group_id: group } }, metadata: { context_token: context } }); }
    failures = 0;
  } catch (e) { failures++; status(a, "reconnecting", e instanceof Error ? e.message : String(e)); await new Promise((r) => setTimeout(r, Math.min(120000, 1000 * 2 ** failures))); } } })();
  return { stop: async () => { stopped = true; }, send: async (address, text) => {
    const user = String(address.id ?? ""), extra = (typeof address.extra === "object" && address.extra ? address.extra : {}) as Record<string, string>, context = extra.context_token || contexts.get(user) || "";
    if (!context) throw new Error("WeChat context_token unavailable"); const res = await fetch(`${base}/ilink/bot/sendmessage`, { method: "POST", headers: wxHeaders(token), body: JSON.stringify({ msg: { from_user_id: "", to_user_id: user, client_id: randomUUID(), message_type: 2, message_state: 2, context_token: context, item_list: [{ type: 1, text_item: { text } }] }, base_info: { channel_version: "2.0.1" } }) });
    if (!res.ok) throw new Error(`WeChat HTTP ${res.status}`);
  } };
}

async function configure(payload: Obj) {
  await stopAll(); const enabled = (await accounts(payload)).filter((a) => a.enabled !== false);
  for (const a of enabled) try { status(a, "connecting"); const adapter = a.type === "feishu" ? await startFeishu(a) : a.type === "dingtalk" ? await startDingTalk(a) : await startWeChat(a); adapters.set(a.account_id, adapter); status(a, "connected"); }
  catch (e) { status(a, "error", e instanceof Error ? e.message : String(e)); }
  return { configured: enabled.length, config_path: configPath };
}

async function handle(req: Obj) {
  const id = String(req.id ?? ""), type = String(req.type ?? ""), payload = (typeof req.payload === "object" && req.payload ? req.payload : {}) as Obj; if (!id) throw new Error("id required");
  if (type === "subscribe") { subscriber = id; emit("channel_ready", await configure(payload)); return; }
  if (type === "reload") { reply(id, await configure(payload)); return; }
  if (type === "send") { const adapter = adapters.get(String(payload.account_id ?? "")); if (!adapter) throw new Error("channel account not connected"); await adapter.send((payload.address ?? {}) as Obj, String(payload.text ?? "")); reply(id, { sent: true }); return; }
  if (type === "stop") { await stopAll(); reply(id, { stopped: true }); return; }
  throw new Error(`unsupported request: ${type}`);
}

const reader = createInterface({ input: stdin, crlfDelay: Infinity });
for await (const line of reader) { if (!line.trim()) continue; let req: Obj; try { req = JSON.parse(line) as Obj; } catch (e) { fail("", e); continue; } handle(req).catch((e) => fail(String(req.id ?? ""), e)); }
await stopAll();
