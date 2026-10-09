// twin-api 혼합 부하: 화면 사용 패턴을 본뜬 조회·분석 요청(browse) + 비동기 분석 Job(jobs)
//   API_BASE   : 요청 대상(twin-api 직접 http://twin-api:8080, nginx 경유 http://frontend:8080)
//   STAGES     : browse 도착률 단계 "지속시간:초당요청,..."(기본 "30s:10,1m:10,30s:30,1m:30,30s:60,1m:60,20s:0")
//   JOB_RATE   : 분당 Job 제출 수(기본 6)
//   VARY_PARAMS: "1"이면 분석 요청 조건(반경·목표 시간)을 매번 바꿔 서버 캐시를 거치지 않는 비용을 잰다
// nginx는 IP당 요청 한도(30r/s)를 두므로 용량 측정은 twin-api 직접 호출로 한다(edge-limits.js가 한도 동작을 따로 본다).
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";
import { loginAll, Session } from "./lib/auth.js";

const API = (__ENV.API_BASE || "http://localhost:8088").replace(/\/$/, "") + "/api/v1";
const PASSWORD = __ENV.DEMO_PASSWORD || "twinpilot-demo";
const VIEWERS = ["viewer", "analyst", "planner"];

function parseStages(spec) {
  return spec.split(",").map((s) => {
    const [duration, target] = s.split(":");
    return { duration, target: Number(target) };
  });
}

export const options = {
  scenarios: {
    browse: {
      executor: "ramping-arrival-rate",
      exec: "browse",
      startRate: 1,
      timeUnit: "1s",
      stages: parseStages(__ENV.STAGES || "30s:10,1m:10,30s:30,1m:30,30s:60,1m:60,20s:0"),
      preAllocatedVUs: 30,
      maxVUs: 200,
    },
    jobs: {
      executor: "constant-arrival-rate",
      exec: "jobs",
      rate: Number(__ENV.JOB_RATE || 6),
      timeUnit: "1m",
      duration: __ENV.JOB_DURATION || "5m",
      preAllocatedVUs: 4,
      maxVUs: 10,
    },
  },
  thresholds: {
    "http_req_failed{scenario:browse}": ["rate<0.01"],
    "http_req_duration{scenario:browse}": ["p(95)<1000"],
    "http_req_duration{name:building_detail}": ["p(95)<300"],
    "http_req_duration{name:building_search}": ["p(95)<500"],
    "http_req_duration{name:stats}": ["p(95)<500"],
    "http_req_duration{name:coverage}": ["p(95)<1000"],
    "http_req_duration{name:response_time}": ["p(95)<1500"],
    "http_req_duration{name:buildings_geojson}": ["p(95)<1500"],
    checks: ["rate>0.99"],
    job_e2e_ms: ["p(95)<60000"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const jobE2e = new Trend("job_e2e_ms", true);
const jobQuota = new Counter("job_quota_rejected");
const jobFailed = new Counter("job_failed");

const sessions = {}; // VU(탭)별 세션: 자기 토큰 체인을 갖고 만료 전에 갱신한다
function auth(data, username) {
  if (!sessions[username]) sessions[username] = new Session(data.sso[username], username);
  return sessions[username].authHeader();
}

export function setup() {
  const sso = loginAll(VIEWERS, PASSWORD);
  const res = http.get(`${API}/buildings/search?limit=200&sort=risk`, {
    headers: new Session(sso.viewer, "viewer").authHeader(), tags: { name: "setup" },
  });
  check(res, { "setup search 200": (r) => r.status === 200 });
  return { sso, buildingIds: res.json("items").map((b) => b.id) };
}

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const VARY = __ENV.VARY_PARAMS === "1";
const between = (lo, hi) => Math.round((lo + Math.random() * (hi - lo)) * 10) / 10;
const json = (headers) => ({ ...headers, "Content-Type": "application/json" });

// 한 반복 = 사용자 한 동작. 가중치는 화면 사용 빈도(상세 조회가 가장 잦고, 전체 레이어는 첫 화면에서만 받는다).
// p = { headers, tags: { name } } — 요청을 동작 이름으로 묶어 집계한다(건물 ID별 URL로 흩어지지 않게).
const ACTIONS = [
  [30, "building_detail", (p, d) => http.get(`${API}/buildings/${pick(d.buildingIds)}`, p)],
  [15, "building_search", (p) => http.get(`${API}/buildings/search?minRisk=60&sort=risk&limit=50`, p)],
  [10, "stats", (p) => http.get(`${API}/stats/summary`, p)],
  [10, "sensors", (p) => http.get(`${API}/sensors`, p)],
  [10, "facilities", (p) => http.get(`${API}/facilities`, p)],
  [5, "buildings_geojson", (p) => http.get(`${API}/buildings`, p)],
  [5, "roads", (p) => http.get(`${API}/roads`, p)],
  [10, "coverage", (p) => http.post(`${API}/analysis/coverage`,
    JSON.stringify({ facilityType: pick(["HYDRANT", "AED", "FIRE_STATION"]), topUncovered: 10,
      ...(VARY ? { radiusM: between(50, 400) } : {}) }),
    { ...p, headers: json(p.headers) })],
  [5, "response_time", (p) => http.post(`${API}/analysis/response`,
    JSON.stringify({ targetMin: VARY ? between(3, 10) : 7, top: 10 }), { ...p, headers: json(p.headers) })],
];
const TOTAL = ACTIONS.reduce((s, a) => s + a[0], 0);

export function browse(data) {
  const headers = auth(data, VIEWERS[__VU % VIEWERS.length]);
  let r = Math.random() * TOTAL;
  for (const [weight, name, fn] of ACTIONS) {
    r -= weight;
    if (r > 0) continue;
    const res = fn({ headers, tags: { name } }, data);
    check(res, { [`${name} 200`]: (x) => x.status === 200 });
    return;
  }
}

export function jobs(data) {
  const headers = json(auth(data, "analyst"));
  const started = Date.now();
  const res = http.post(`${API}/analysis/jobs`,
    JSON.stringify({ type: "RECOMMEND_SITES", facilityType: "HYDRANT", count: 3 }),
    { headers: { ...headers, "Idempotency-Key": `k6-${__VU}-${__ITER}-${started}` }, tags: { name: "job_submit" } });
  if (res.status === 429) {
    jobQuota.add(1); // 사용자별 동시 Job 한도 초과는 정상 동작이다
    return;
  }
  if (!check(res, { "job accepted": (r) => r.status === 202 || r.status === 200 })) return;
  const id = res.json("id");
  for (let i = 0; i < 120; i++) {
    sleep(1);
    const st = http.get(`${API}/analysis/jobs/${id}`, { headers, tags: { name: "job_poll" } });
    const status = st.json("status");
    if (status === "SUCCEEDED" || status === "FAILED" || status === "CANCELLED") {
      check(status, { "job succeeded": (s) => s === "SUCCEEDED" });
      if (status !== "SUCCEEDED") jobFailed.add(1);
      jobE2e.add(Date.now() - started);
      return;
    }
  }
  check(null, { "job finished within 120s": () => false });
}
