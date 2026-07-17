# External channels

Aether can receive messages from Feishu, DingTalk and WeChat iLink, route them into normal Aether chat sessions, and send the final Agent response back to the original conversation.

The design follows QwenPaw's Channel model:

```text
platform message -> normalized channel message -> deterministic session id
                 -> SessionExecutionManager -> platform reply
```

Platform SDKs run in an isolated Node process inside Aether's Alpine runtime. Its stdout is JSONL protocol only; SDK logs are redirected to stderr.

## Configuration

Create `/root/.aether/channels.json` inside the Aether Alpine runtime. The service reloads the file when the app process starts.

```json
{
  "accounts": [
    {
      "account_id": "feishu-main",
      "type": "feishu",
      "display_name": "Company Feishu",
      "enabled": true,
      "credentials": {
        "app_id": "cli_xxx",
        "app_secret": "xxx"
      },
      "options": {
        "domain": "feishu",
        "require_mention": true,
        "share_session_in_group": false
      }
    },
    {
      "account_id": "dingtalk-main",
      "type": "dingtalk",
      "enabled": true,
      "credentials": {
        "client_id": "dingxxx",
        "client_secret": "xxx"
      },
      "options": {
        "share_session_in_group": true
      }
    },
    {
      "account_id": "wechat-main",
      "type": "wechat",
      "enabled": true,
      "credentials": {
        "bot_token": "xxx"
      },
      "options": {
        "base_url": "https://ilinkai.weixin.qq.com",
        "share_session_in_group": true
      }
    }
  ]
}
```

Secrets remain inside the Alpine runtime configuration file and are never placed in Agent messages or Android logs.

## Session mapping

- Direct message: `channel:<type>:<account>:user:<sender>`
- Shared group session: `channel:<type>:<account>:group:<conversation>`
- Isolated group member: `channel:<type>:<account>:group:<conversation>:user:<sender>`

Feishu defaults to per-member group sessions. DingTalk and WeChat default to shared group sessions. Override with `share_session_in_group`.

## Platform setup

- Feishu: enable the bot, choose WebSocket long connection, and subscribe to message events.
- DingTalk: enable a Stream-mode application bot.
- WeChat: provide an iLink Bot token. This adapter is not an unofficial desktop-client automation bot.

The first implementation sends the final Agent text response. The event protocol and adapter boundary are intentionally isolated so card streaming and media handling can be added without changing Aether's Agent core.
