# @baimoqilin/aether-extension-api

TypeScript declarations for Aether Script Extensions.

```bash
npm install --save-dev @baimoqilin/aether-extension-api
```

```ts
import { defineAetherExtension, ui } from "@baimoqilin/aether-extension-api";

export const activateAether = defineAetherExtension((aether) => {
  aether.registerSurface("chat.composer.top", {
    render: () => ui.text("Hello from Aether"),
  });
});
```

This package intentionally contains declarations only. Aether injects the
runtime implementation when it loads an Extension.

The npm package major version matches `aether.apiVersion`. Package `2.x`
describes Script API version 2.
