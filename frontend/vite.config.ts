/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteStaticCopy } from "vite-plugin-static-copy";

// Cesium의 Workers/Assets/Widgets/ThirdParty를 정적 경로로 복사하고 CESIUM_BASE_URL로 알려준다.
const cesiumSource = "node_modules/cesium/Build/Cesium";
const cesiumBaseUrl = "cesiumStatic";

const apiTarget = process.env.API_TARGET ?? "http://localhost:8080";
const agentTarget = process.env.AGENT_TARGET ?? "http://localhost:8000";

export default defineConfig({
  define: {
    CESIUM_BASE_URL: JSON.stringify(`/${cesiumBaseUrl}`),
  },
  plugins: [
    react(),
    viteStaticCopy({
      // v4는 원본 경로 구조를 유지하므로 node_modules/cesium/Build/Cesium(4단계)을 잘라낸다.
      targets: ["ThirdParty", "Workers", "Assets", "Widgets"].map((dir) => ({
        src: `${cesiumSource}/${dir}`,
        dest: cesiumBaseUrl,
        rename: { stripBase: 4 },
      })),
    }),
  ],
  server: {
    port: 5173,
    // Host를 바꾸지 않아야(changeOrigin: false) 백엔드가 동일 출처 요청으로 보고 CORS 검사를 하지 않는다.
    proxy: {
      "/api": { target: apiTarget },
      "/agent": { target: agentTarget },
    },
  },
  build: {
    chunkSizeWarningLimit: 6000,
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts"],
  },
});
