// 3D 모델 E2E: 샘플 GLB 업로드 → READY → 위치 이동 → Three.js 미리보기
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
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => m.type() === "error" && errors.push(m.text()));

await page.goto(base, { waitUntil: "networkidle" });
await page.waitForTimeout(5000);
await page.getByRole("button", { name: "접기" }).click(); // 코파일럿 접기
await page.getByRole("tab", { name: "3D 모델" }).click();
await page.getByRole("button", { name: "샘플 모델" }).click();
await page.waitForSelector(".model-item .chip:text('READY')", { timeout: 30000 });
const stats = await page.locator(".model-item .muted").first().textContent();
await page.getByRole("button", { name: "위치로 이동" }).first().click();
await page.waitForTimeout(3000);
await page.getByRole("button", { name: "Three.js 미리보기" }).first().click();
await page.waitForSelector(".three-view canvas", { timeout: 30000 });
await page.waitForTimeout(3000);
const status = await page.locator(".models .muted.small").last().textContent();
await page.screenshot({ path: `${out}/09-models.png` });
console.log(JSON.stringify({ stats, status, errors }, null, 1));
await browser.close();
