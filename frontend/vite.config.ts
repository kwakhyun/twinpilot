/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteStaticCopy } from "vite-plugin-static-copy";

// Cesium의 Workers/Assets/ThirdParty를 정적 경로로 복사하고 CESIUM_BASE_URL로 알려준다.
// 코드는 @cesium/engine에서만 가져온다(위젯 패키지의 Knockout은 eval을 써서 엄격한 CSP와 맞지 않는다).
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
      targets: ["ThirdParty", "Workers", "Assets"].map((dir) => ({
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
    rolldownOptions: {
      output: {
        // 자주 바뀌는 앱 코드와 거의 바뀌지 않는 라이브러리를 분리해 배포 후에도 브라우저 캐시를 재사용한다.
        codeSplitting: {
          groups: [
            { name: "cesium", test: /node_modules[\\/](cesium|@cesium|@tweenjs|ktx-parse|meshoptimizer|protobufjs|@protobufjs|lerc|earcut|kdbush|rbush|topojson-client|grapheme-splitter|bitmap-sdf|jsep|draco3d|nosleep\.js|urijs|autolinker|dompurify|mersenne-twister|pako|long)[\\/]/, priority: 20 },
            { name: "react", test: /node_modules[\\/](react|react-dom|scheduler|@tanstack|zustand)[\\/]/, priority: 10 },
            { name: "auth", test: /node_modules[\\/](oidc-client-ts|jwt-decode)[\\/]/, priority: 10 },
          ],
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts"],
  },
});
