// TwinPilot 브라우저 E2E: 실제 Keycloak 로그인(역할별) → 화면 시나리오 → 접근성(axe) → CSP 위반 확인.
//   BASE=http://localhost:8088 DEMO_PASSWORD=... node suite.mjs
//   자체 서명 인증서(경량판 로컬 시험 https://localhost)에서는 IGNORE_HTTPS_ERRORS=1
// 각 시나리오의 스크린숏을 OUT 폴더에 남기고, 실패하면 종료 코드 1로 끝난다.
import { mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import AxeBuilder from "@axe-core/playwright";
import { chromium } from "playwright";

const base = process.env.BASE ?? "http://localhost:8088";
const out = process.env.OUT ?? "./screenshots";
const password = process.env.DEMO_PASSWORD ?? "twinpilot-demo";
mkdirSync(out, { recursive: true });

// GPU 없는 환경에서도 WebGL(Cesium·Three.js)이 돌도록 SwiftShader를 쓴다.
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"],
});

const results = [];
const problems = [];

async function scenario(name, fn) {
  const started = Date.now();
  try {
    await fn();
    results.push({ name, ok: true, ms: Date.now() - started });
    console.log(`✔ ${name} (${Date.now() - started}ms)`);
  } catch (e) {
    results.push({ name, ok: false, ms: Date.now() - started, error: String(e.message ?? e).split("\n")[0] });
    console.log(`✘ ${name}: ${String(e.message ?? e).split("\n")[0]}`);
  }
}

/** 역할별 브라우저 컨텍스트(쿠키·세션 분리). Keycloak 로그인 폼을 실제로 채운다. */
async function loginAs(username) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 900 }, locale: "ko-KR",
    ignoreHTTPSErrors: process.env.IGNORE_HTTPS_ERRORS === "1" });
  const page = await context.newPage();
  page.on("console", (m) => {
    const text = m.text();
    if (m.type() === "error" || /Content Security Policy|Refused to/.test(text)) problems.push(`[${username}] ${m.type()}: ${text}`);
  });
  page.on("pageerror", (e) => problems.push(`[${username}] pageerror: ${e.message}`));
  await page.goto(base);
  await page.waitForURL(/\/auth\/realms\/twinpilot\/protocol\/openid-connect\/auth/, { timeout: 30000 });
  await page.fill("#username", username);
  await page.fill("#password", password);
  await page.click("#kc-login");
  await page.waitForSelector(".topbar .user-name", { timeout: 60000 });
  await page.waitForTimeout(6000); // Cesium 초기 렌더링
  return { context, page };
}

const shot = (page, file) => page.screenshot({ path: join(out, file) });

// ------------------------------------------------------------------ VIEWER
const viewer = await loginAs("viewer");
await scenario("viewer: 로그인 후 역할 표시, 관리 탭 없음", async () => {
  const { page } = viewer;
  await page.getByText("조회", { exact: true }).waitFor();
  if (await page.getByRole("tab", { name: "관리" }).count()) throw new Error("VIEWER에게 관리 탭이 보임");
  await shot(page, "01-overview.png");
});
await scenario("viewer: 커버리지 분석·건물 상세", async () => {
  const { page } = viewer;
  await page.getByRole("button", { name: "커버리지 분석" }).click();
  await page.waitForSelector("text=우선 조치 대상");
  await page.waitForTimeout(3000);
  await shot(page, "02-coverage.png");
  await page.locator(".item-list .item").first().click();
  await page.waitForSelector(".detail .risk-score");
  await page.waitForSelector("text=도로망 최단경로");
  await page.waitForTimeout(3000);
  await shot(page, "03-building.png");
});
await scenario("viewer: 출동 시간 분석·도달권", async () => {
  const { page } = viewer;
  await page.locator(".response select").nth(1).selectOption("4");
  await page.getByRole("button", { name: "출동 시간 분석" }).click();
  await page.waitForSelector("text=목표 초과 · 위험도 높은 순");
  await page.waitForSelector(".map-legend >> text=출동 도달권(도로망)");
  await page.waitForTimeout(3000);
  await shot(page, "04-response-isochrones.png");
});
await scenario("viewer: 최적입지 추천 버튼 비활성(권한 없음)", async () => {
  const { page } = viewer;
  const disabled = await page.getByRole("button", { name: "추천 실행" }).isDisabled();
  if (!disabled) throw new Error("VIEWER가 추천을 실행할 수 있음");
});
await scenario("viewer: 접근성(axe) 심각·치명 위반 없음", async () => {
  const { page } = viewer;
  const res = await new AxeBuilder({ page }).exclude(".cesium-viewer").exclude(".map").analyze();
  const bad = res.violations.filter((v) => ["serious", "critical"].includes(v.impact));
  writeFileSync(join(out, "axe-viewer.json"), JSON.stringify(res.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, help: v.help })), null, 2));
  if (bad.length) throw new Error(bad.map((v) => `${v.id}(${v.nodes.length})`).join(", "));
});
await scenario("viewer: 코파일럿 출동 시간 질문 → 도구 호출·지도 액션", async () => {
  const { page } = viewer;
  await page.getByRole("button", { name: "소방차가 4분 안에 도착하지 못하는 건물은 어디야?" }).click();
  await page.waitForSelector(".copilot .msg.assistant .content >> text=출동 4분 이내", { timeout: 60000 });
  await page.waitForSelector(".copilot .chip >> text=출동 도달권");
  await page.waitForTimeout(2500);
  await shot(page, "05-copilot-response.png");
});
await scenario("viewer: 새로고침해도 대화 복원", async () => {
  const { page } = viewer;
  await page.reload();
  await page.waitForSelector(".copilot .msg.user >> text=4분 안에 도착하지", { timeout: 30000 });
});

await viewer.context.close(); // WebGL(SwiftShader) 컨텍스트를 동시에 여러 개 띄우지 않는다.

// ------------------------------------------------------------------ ANALYST
const analyst = await loginAs("analyst");
await scenario("analyst: 추천 Job(SSE 진행률) 실행, 계획 배치는 비활성", async () => {
  const { page } = analyst;
  await page.locator(".form-grid select").first().selectOption("AED");
  await page.getByRole("button", { name: "추천 실행" }).click();
  await page.waitForSelector("text=계획 시설로 배치하고 재분석", { timeout: 60000 });
  await page.waitForTimeout(2500);
  await shot(page, "06-recommend.png");
  if (!(await page.getByRole("button", { name: "계획 시설로 배치하고 재분석" }).isDisabled())) throw new Error("ANALYST가 계획 배치 가능");
});

await analyst.context.close();

// ------------------------------------------------------------------ PLANNER
const planner = await loginAs("planner");
await scenario("planner: 추천 → 계획 배치 → 재분석", async () => {
  const { page } = planner;
  await page.locator(".form-grid select").first().selectOption("AED");
  await page.locator(".form-grid select").nth(1).selectOption({ label: "한빛동" });
  await page.getByRole("button", { name: "추천 실행" }).click();
  await page.getByRole("button", { name: "계획 시설로 배치하고 재분석" }).click({ timeout: 60000 });
  await page.waitForSelector(".tile-delta", { timeout: 30000 });
  await page.waitForTimeout(2000);
  await shot(page, "07-planned.png");
  await page.getByRole("button", { name: "계획(PLANNED) 시설 모두 지우기" }).click();
  await page.waitForSelector(".toast >> text=계획 시설");
});
await scenario("planner: 3D 모델 업로드 → 검증(Job) → 미리보기", async () => {
  const { page } = planner;
  await page.getByRole("tab", { name: "3D 모델" }).click();
  await page.getByRole("button", { name: "샘플 모델" }).click();
  await page.waitForSelector(".model-item .chip:text-is('READY')", { timeout: 30000 });
  await page.getByRole("button", { name: "Three.js 미리보기" }).first().click();
  await page.waitForSelector("text=(W×H×D)", { timeout: 30000 });
  await page.waitForTimeout(2000);
  await shot(page, "08-models.png");
});

await planner.context.close();

// ------------------------------------------------------------------ ADMIN
const admin = await loginAs("admin");
await scenario("admin: GeoJSON 검증(dry run) → 적재 → 감사 로그", async () => {
  const { page } = admin;
  await page.getByRole("tab", { name: "관리" }).click();
  const file = join(tmpdir(), "e2e-hydrants.geojson");
  writeFileSync(file, JSON.stringify({
    type: "FeatureCollection",
    features: [
      { type: "Feature", id: "E2E-1", properties: { NM: "E2E 소화전 1" }, geometry: { type: "Point", coordinates: [129.0571, 35.1588] } },
      { type: "Feature", id: "E2E-2", properties: { NM: "E2E 소화전 2" }, geometry: { type: "Point", coordinates: [10, 10] } },
      { type: "Feature", id: "E2E-3", properties: { NM: "선 지오메트리" }, geometry: { type: "LineString", coordinates: [[129.05, 35.15], [129.06, 35.16]] } },
    ],
  }));
  await page.locator(".admin input[type=file]").setInputFiles(file);
  await page.locator(".admin select").first().selectOption("HYDRANT");
  await page.getByPlaceholder("예: building-2026q3").fill("e2e-hydrant");
  await page.locator(".admin textarea").fill('{"fields": {"name": "NM"}}');
  await page.getByRole("button", { name: "검증 실행" }).click();
  await page.waitForSelector("text=검증만 했습니다", { timeout: 60000 });
  await shot(page, "09-admin-dryrun.png");
  await page.getByText("검증만 하기(dry run)").click();
  await page.getByRole("button", { name: "적재 실행" }).click();
  await page.waitForSelector(".toast >> text=적재 완료", { timeout: 120000 });
  await page.waitForSelector(".audit >> text=DATA_IMPORT", { timeout: 15000 });
  await page.waitForTimeout(1000);
  await shot(page, "10-admin-import.png");
});
await scenario("admin: 로그아웃 → 로그인 화면", async () => {
  const { page } = admin;
  await page.getByRole("button", { name: "로그아웃" }).click();
  await page.waitForURL(/\/auth\/realms\/twinpilot\/protocol\/openid-connect\/auth|\/logout/, { timeout: 30000 });
});

await admin.context.close();
await browser.close();

const csp = problems.filter((p) => /Content Security Policy|Refused to/.test(p));
writeFileSync(join(out, "e2e-result.json"), JSON.stringify({ results, problems }, null, 2));
console.log(`\n시나리오 ${results.filter((r) => r.ok).length}/${results.length} 통과, 콘솔 오류 ${problems.length}건(CSP 위반 ${csp.length}건)`);
for (const p of problems.slice(0, 20)) console.log("  " + p.slice(0, 300));
process.exit(results.every((r) => r.ok) && csp.length === 0 ? 0 : 1);
