// TwinPilot 시연 영상 녹화: CDP screencast로 프레임(JPEG)과 시각을 저장하고, 자막·구간 정보를 markers.json에 남긴다.
//   cd twinpilot/e2e && CHROMIUM_PATH=... VID=<출력 폴더> node <이 파일>
import { mkdirSync, writeFileSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { createRequire } from "node:module";
const require = createRequire(join(process.cwd(), "package.json"));
const { chromium } = require("playwright");

const VID = process.env.VID;
const BASE = process.env.BASE ?? "http://localhost:8088";
const FR = join(VID, "frames");
mkdirSync(FR, { recursive: true });

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH, args: ["--hide-scrollbars"] });
const context = await browser.newContext({ viewport: { width: 1600, height: 900 }, locale: "ko-KR", deviceScaleFactor: 1 });

// 헤드리스에는 커서가 없으므로 커서와 클릭 효과를 그린다(CSP 영향을 받지 않도록 CSSOM으로만 스타일 지정).
await context.addInitScript(() => {
  const install = () => {
    if (document.getElementById("__cur")) return;
    const c = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    c.id = "__cur";
    c.setAttribute("width", "26"); c.setAttribute("height", "30"); c.setAttribute("viewBox", "0 0 26 30");
    c.innerHTML = '<path d="M2 2 L2 24 L8 18.5 L12.5 28 L16.5 26 L12 16.8 L20 16.8 Z" fill="#fff" stroke="#111" stroke-width="1.6" stroke-linejoin="round"/>';
    Object.assign(c.style, { position: "fixed", left: "0", top: "0", zIndex: "2147483647", pointerEvents: "none",
      transform: `translate(${window.__mx ?? -40}px, ${window.__my ?? -40}px)`, filter: "drop-shadow(0 1px 2px rgba(0,0,0,.5))",
      transition: "transform .32s cubic-bezier(.25,.8,.3,1)" });
    document.documentElement.appendChild(c);
    document.addEventListener("mousemove", (e) => {
      window.__mx = e.clientX; window.__my = e.clientY;
      c.style.transform = `translate(${e.clientX - 2}px, ${e.clientY - 2}px)`;
    }, true);
    document.addEventListener("mousedown", (e) => {
      const r = document.createElement("div");
      Object.assign(r.style, { position: "fixed", left: `${e.clientX - 18}px`, top: `${e.clientY - 18}px`, width: "36px",
        height: "36px", borderRadius: "50%", border: "3px solid rgba(59,140,255,.95)", background: "rgba(59,140,255,.25)",
        zIndex: "2147483646", pointerEvents: "none", transition: "transform .45s ease-out, opacity .45s ease-out" });
      document.documentElement.appendChild(r);
      requestAnimationFrame(() => { r.style.transform = "scale(1.9)"; r.style.opacity = "0"; });
      setTimeout(() => r.remove(), 600);
    }, true);
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", install); else install();
});

const page = await context.newPage();
const cdp = await context.newCDPSession(page);
const frames = [];
const markers = [];
let recording = false;
let n = 0;
cdp.on("Page.screencastFrame", async ({ data, metadata, sessionId }) => {
  try {
    if (recording) {
      const f = `f${String(++n).padStart(6, "0")}.jpg`;
      writeFileSync(join(FR, f), Buffer.from(data, "base64"));
      frames.push({ t: metadata.timestamp, f });
    }
    await cdp.send("Page.screencastFrameAck", { sessionId });
  } catch { /* 페이지 이동 중 */ }
});
await cdp.send("Page.startScreencast", { format: "jpeg", quality: 88, maxWidth: 1600, maxHeight: 900, everyNthFrame: 1 });

const now = () => Date.now() / 1000;
// 의도한 "보여 주는" 대기는 hold로 표시한다. 편집에서 hold는 실제 속도, 나머지(조작·로딩)는 빠르게 재생한다.
const wait = async (ms) => { mark("hold"); await page.waitForTimeout(ms); mark("act"); };
const mark = (type, text = "") => markers.push({ t: now(), type, text });
const rec = (on) => { recording = on; mark(on ? "resume" : "pause"); };
const sub = (text) => mark("sub", text);
const title = (text) => mark("title", text);
let mx = 800, my = 450;
async function moveTo(x, y, steps = 6) { await page.mouse.move(x, y, { steps }); mx = x; my = y; }
async function point(locator) {
  await locator.scrollIntoViewIfNeeded();
  const b = await locator.boundingBox();
  if (!b) throw new Error("요소 위치를 찾지 못함");
  await moveTo(b.x + b.width / 2, b.y + b.height / 2);
  await page.waitForTimeout(380);   // CSS 전환이 끝날 때까지(조작 구간)
}
async function click(locator) {
  await point(locator);
  await page.mouse.down(); await page.mouse.up();
}
async function select(locator, value) { await point(locator); await locator.selectOption(value); await wait(400); }
const btn = (name) => page.getByRole("button", { name, exact: true });

// ---------------------------------------------------------------- 0. 인트로
await page.setContent(readFileSync(join(VID, "..", "intro.html"), "utf8"));
await page.evaluate(() => document.fonts.ready);
rec(true); mark("section", "intro");
await wait(9500);
rec(false);

// ---------------------------------------------------------------- 1. 로그인
await page.goto(BASE);
await page.waitForSelector("#username");
await moveTo(700, 300, 2);
rec(true); title("역할 기반 로그인");
sub("Keycloak(OIDC) 로그인 — 계획 담당자(planner) 역할로 접속합니다");
await wait(1200);
await click(page.locator("#username"));
await page.locator("#username").pressSequentially("planner", { delay: 90 });
await click(page.locator("#password"));
await page.locator("#password").pressSequentially("twinpilot-demo", { delay: 45 });
await wait(900);
await click(page.locator("#kc-login"));
rec(false);
await page.getByRole("tab", { name: "대시보드" }).waitFor({ timeout: 60000 });
await wait(7000);                       // 3D 타일·건물 로딩은 녹화하지 않는다
await moveTo(1150, 640, 2);

// ---------------------------------------------------------------- 2. 개요
rec(true); title("3D 디지털트윈");
sub("부산진구 일대 좌표에 만든 합성 도시 — 건물 2,309동을 3D로 표시합니다");
await moveTo(1000, 420); await wait(2600);
sub("건물 색은 노후도·구조·용도·층수와 소방 접근성을 합친 화재위험도입니다");
await click(page.getByRole("tab", { name: "레이어" }));
await wait(900);
await click(page.getByRole("radio", { name: "노후도", exact: true }));
await wait(2600);
await click(page.getByRole("radio", { name: "화재위험도", exact: true }));
await wait(1600);

// ---------------------------------------------------------------- 3. 대시보드
title("대시보드 · 실시간 센서");
sub("법정 기준 커버리지와 위험 분포, IoT 센서 111개의 상태를 실시간(SSE)으로 보여 줍니다");
await click(page.getByRole("tab", { name: "대시보드" }));
await wait(2800);
await point(page.getByText(/저수압/).first()).catch(() => {});
sub("소화전 수압 센서가 기준 아래로 떨어지면 상단과 지도에 바로 경보가 뜹니다");
await wait(3200);

// ---------------------------------------------------------------- 4. 커버리지
title("커버리지 · 사각지대 분석");
await click(page.getByRole("tab", { name: "분석" }));
sub("소화전 반경(소방기본법 시행규칙 기준) 밖의 사각지대 건물을 PostGIS로 계산합니다");
await wait(600);
await click(btn("커버리지 분석"));
await page.waitForSelector("text=우선 조치 대상");
await wait(2400);
sub("화재위험도가 높은 순으로 우선 조치 대상을 정리합니다");
await point(page.locator(".item-list .item").first());
await wait(1600);

// ---------------------------------------------------------------- 5. 건물 상세
title("건물 상세");
sub("건물을 고르면 위험 요인, 가장 가까운 소방서까지의 도로망 경로, Three.js 단면을 보여 줍니다");
await click(page.locator(".item-list .item").first());
await page.waitForSelector(".detail .risk-score");
await page.waitForSelector("text=도로망 최단경로");
await wait(4800);

// ---------------------------------------------------------------- 6. 출동 시간
title("도로망 출동 시간");
sub("소방서에서 도로망을 따라 목표 시간 안에 닿는 범위(도달권)를 계산합니다");
await select(page.locator(".response select").nth(1), "4");
await click(btn("출동 시간 분석"));
await page.waitForSelector("text=목표 초과 · 위험도 높은 순");
await page.waitForSelector(".map-legend >> text=출동 도달권(도로망)");
await wait(2200);
sub("4분 안에 닿지 못하는 건물을 위험도 순으로 찾아냅니다");
await moveTo(1050, 470);
await wait(2600);

// ---------------------------------------------------------------- 7. 최적입지
title("최적입지 추천 · 계획 반영");
sub("최적입지 추천(MCLP)은 비동기 작업 큐로 실행되고, 진행률이 실시간으로 표시됩니다");
await select(page.locator(".form-grid select").first(), "AED");
await select(page.locator(".form-grid select").nth(1), { label: "한빛동" });
await click(btn("추천 실행"));
const place = page.getByRole("button", { name: "계획 시설로 배치하고 재분석" });
await place.waitFor({ timeout: 60000 });
await wait(2200);
sub("추천 위치를 계획 시설로 배치하면 커버리지를 바로 다시 계산합니다");
await click(place);
await page.waitForSelector(".tile-delta", { timeout: 30000 });
await wait(1200);
await point(page.locator(".tile-delta").first());
await wait(3000);
rec(false);
await page.getByRole("button", { name: "계획(PLANNED) 시설 모두 지우기" }).click();
await page.waitForSelector(".toast >> text=계획 시설");
await wait(1500);

// ---------------------------------------------------------------- 8. AI 코파일럿
rec(true); title("AI 코파일럿");
sub("자연어로 물으면 에이전트(LangGraph)가 MCP 도구를 호출해 분석합니다");
await click(page.getByRole("button", { name: "소방차가 4분 안에 도착하지 못하는 건물은 어디야?" }));
await page.waitForSelector(".copilot .msg.assistant .content >> text=출동 4분 이내", { timeout: 60000 });
await page.waitForSelector(".copilot .chip >> text=출동 도달권");
await wait(1800);
sub("답변의 수치는 도구 결과에서만 가져오고, 근거를 지도에 바로 표시합니다");
await moveTo(1100, 420);
await wait(3000);
sub("※ 이 영상은 LLM 키 없이, 같은 도구 경로를 쓰는 규칙 기반 모드로 촬영했습니다");
await wait(3200);

// ---------------------------------------------------------------- 9. 3D 모델
title("3D 모델(glTF) 관리");
sub("glTF 모델을 올리면 검증 작업을 거쳐 미리보기와 지도 배치를 할 수 있습니다");
await click(page.getByRole("tab", { name: "3D 모델" }));
await wait(600);
await click(page.getByRole("button", { name: "샘플 모델" }));
await page.waitForSelector(".model-item .chip:text-is('READY')", { timeout: 30000 });
await wait(800);
await click(page.getByRole("button", { name: "Three.js 미리보기" }).first());
await page.waitForSelector("text=(W×H×D)", { timeout: 30000 });
await wait(3800);
rec(false);

// ---------------------------------------------------------------- 10. 아웃트로
await page.setContent(readFileSync(join(VID, "..", "outro.html"), "utf8"));
await page.evaluate(() => document.fonts.ready);
rec(true); mark("section", "outro");
await wait(8500);
rec(false);
mark("end");

await cdp.send("Page.stopScreencast");
writeFileSync(join(VID, "frames.json"), JSON.stringify(frames));
writeFileSync(join(VID, "markers.json"), JSON.stringify(markers, null, 1));
console.log(`프레임 ${frames.length}개, 마커 ${markers.length}개`);
await browser.close();
