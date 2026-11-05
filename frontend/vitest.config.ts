import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";
export default defineConfig({
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  esbuild: { jsx: "automatic" },
  test: {
    environment: "jsdom",
    include: ["src/pages/admin/settings/RuntimeSettingsPanel.test.tsx"],
    pool: "forks",
    poolOptions: { forks: { singleFork: true } }
  }
});
