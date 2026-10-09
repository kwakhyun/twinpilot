// 프론트엔드 E2E 스모크: 지도 렌더링, 건물 선택, 커버리지 분석, 대시보드를 캡처한다.
import { chromium } from "playwright";

const base = process.env.BASE ?? "http://localhost:8088";
const out = process.env.OUT ?? "./screenshots";
await import("node:fs").then((fs) => fs.mkdirSync(out, { recursive: true }));
// GPU 없는 환경에서도 WebGL(Cesium·Three.js)이 돌도록 SwiftShader를 쓴다. CHROMIUM_PATH로 브라우저를 지정할 수 있다.
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"],
});
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
const logs = [];
page.on("console", (m) => {
  if (["error", "warning"].includes(m.type())) logs.push(`[${m.type()}] ${m.text()}`);
});
page.on("pageerror", (e) => logs.push(`[pageerror] ${e.message}`));

await page.goto(base, { waitUntil: "networkidle" });
await page.waitForTimeout(8000);
await page.screenshot({ path: `${out}/01-overview.png` });

// 커버리지 분석(기본: 소화전 전체)
await page.getByRole("button", { name: "커버리지 분석" }).click();
await page.waitForSelector("text=우선 조치 대상");
await page.waitForTimeout(4000);
await page.screenshot({ path: `${out}/02-coverage.png` });

// 위험도 1위 미커버 건물 선택 → 상세 패널 + Three.js 단면
await page.locator(".item-list .item").first().click();
await page.waitForSelector(".detail .risk-score");
await page.waitForTimeout(4000);
await page.screenshot({ path: `${out}/03-building.png` });

// 추천 Job (SSE 진행률)
await page.getByRole("button", { name: "추천 실행" }).click();
await page.waitForSelector("text=계획 시설로 배치하고 재분석", { timeout: 60000 });
await page.waitForTimeout(3000);
await page.screenshot({ path: `${out}/04-recommend.png` });

// 대시보드
await page.getByRole("tab", { name: "대시보드" }).click();
await page.waitForSelector(".hero-value");
await page.locator(".chart-svg .bar").first().hover();
await page.waitForTimeout(800);
await page.screenshot({ path: `${out}/05-dashboard.png` });

console.log(logs.slice(0, 30).join("\n") || "콘솔 오류 없음");
await browser.close();
