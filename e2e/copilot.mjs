// AI 코파일럿 E2E: 예시 질문 → 도구 호출 트레이스·지도 액션·답변 확인, 후속 질문(세션 유지)과 건물 브리핑.
import { chromium } from "playwright";

const out = process.env.OUT ?? "./screenshots";
await import("node:fs").then((fs) => fs.mkdirSync(out, { recursive: true }));
// GPU 없는 환경에서도 WebGL(Cesium·Three.js)이 돌도록 SwiftShader를 쓴다. CHROMIUM_PATH로 브라우저를 지정할 수 있다.
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"],
});
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => m.type() === "error" && errors.push(m.text()));

await page.goto(process.env.BASE ?? "http://localhost:8088", { waitUntil: "networkidle" });
await page.waitForTimeout(6000);

await page.getByRole("button", { name: /한빛동에 AED 3대/ }).click();
await page.waitForSelector(".msg.assistant .content:has-text('커버리지')", { timeout: 60000 });
await page.waitForTimeout(4000);
await page.screenshot({ path: `${out}/06-copilot-recommend.png` });
const chips1 = await page.locator(".msg.assistant .chip").allTextContents();

await page.getByLabel("코파일럿에게 질문").fill("다솜동 소화전 사각지대 노후 건물 보여줘");
await page.getByRole("button", { name: "보내기" }).click();
await page.waitForFunction(() => document.querySelectorAll(".msg.assistant .content").length >= 2, null, { timeout: 60000 });
await page.waitForTimeout(4000);
await page.screenshot({ path: `${out}/07-copilot-search.png` });

await page.getByLabel("코파일럿에게 질문").fill("건물 696번 화재 대응 브리핑을 해줘");
await page.getByRole("button", { name: "보내기" }).click();
await page.waitForFunction(() => document.querySelectorAll(".msg.assistant .content").length >= 3, null, { timeout: 60000 });
await page.waitForSelector(".detail .risk-score");
await page.waitForTimeout(4000);
await page.screenshot({ path: `${out}/08-copilot-briefing.png` });

const steps = await page.locator(".steps code").allTextContents();
console.log(JSON.stringify({ steps, chips1, legendNote: await page.locator(".legend-note").textContent(), errors }, null, 1));
await browser.close();
