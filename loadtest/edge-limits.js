// 경계 보호 동작 확인(nginx 경유): 한도를 넘는 요청이 5xx 없이 429로 거절되는지, 권한 경계가 지켜지는지 본다.
//   API_BASE: 프론트엔드(nginx) 주소. compose 네트워크 안에서는 http://frontend:8080
//   - api_burst : 한 IP에서 초당 API_RATE(기본 80)건 → nginx IP 한도(30r/s, burst 60) 초과분은 429
//   - agent     : 한 사용자가 초당 1건씩 질문 → 사용자별 한도(분당 20, burst 5)·IP 한도(2r/s) 초과분은 429
//   - rbac      : 익명 401, VIEWER의 Job 생성 403, ADMIN 전용 API 403
import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";
import { loginAll, Session } from "./lib/auth.js";

const BASE = (__ENV.API_BASE || "http://localhost:8088").replace(/\/$/, "");
const PASSWORD = __ENV.DEMO_PASSWORD || "twinpilot-demo";

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 401, 403, 429));

export const options = {
  scenarios: {
    api_burst: {
      executor: "constant-arrival-rate", exec: "apiBurst",
      rate: Number(__ENV.API_RATE || 80), timeUnit: "1s", duration: "20s",
      preAllocatedVUs: 40, maxVUs: 100,
    },
    agent: {
      executor: "constant-arrival-rate", exec: "agent",
      rate: 1, timeUnit: "1s", duration: "15s", preAllocatedVUs: 5, maxVUs: 20,
      startTime: "25s",
    },
    rbac: { executor: "shared-iterations", exec: "rbac", iterations: 1, vus: 1, startTime: "45s" },
  },
  thresholds: {
    http_req_failed: ["rate==0"],               // 2xx·401·403·429 외 응답(특히 5xx)이 없어야 한다
    "rate_limited{scenario:api_burst}": ["count>0"],
    "rate_limited{scenario:agent}": ["count>0"],
    "agent_answer_ms": ["p(95)<5000"],
    checks: ["rate==1"],
  },
  summaryTrendStats: ["avg", "med", "p(95)", "max"],
};

const limited = new Counter("rate_limited");
const accepted = new Counter("accepted");
const agentAnswer = new Trend("agent_answer_ms", true);

const sessions = {};
function auth(data, username) {
  if (!sessions[username]) sessions[username] = new Session(data.sso[username], username);
  return sessions[username].authHeader();
}

export function setup() {
  return { sso: loginAll(["viewer", "analyst"], PASSWORD) };
}

export function apiBurst(data) {
  const res = http.get(`${BASE}/api/v1/stats/summary`, { headers: auth(data, "viewer"), tags: { name: "stats" } });
  check(res, { "200 or 429": (r) => r.status === 200 || r.status === 429 });
  (res.status === 429 ? limited : accepted).add(1);
}

export function agent(data) {
  const res = http.post(`${BASE}/agent/chat`,
    JSON.stringify({ message: "위험도가 가장 높은 건물 3곳을 알려줘", sessionId: `k6-${__VU}-${__ITER}` }),
    { headers: { ...auth(data, "viewer"), "Content-Type": "application/json" }, tags: { name: "agent_chat" }, timeout: "60s" });
  check(res, { "200 or 429": (r) => r.status === 200 || r.status === 429 });
  if (res.status === 429) {
    limited.add(1);
    return;
  }
  accepted.add(1);
  check(res, { "answer streamed": (r) => r.body.includes("event: message") || r.body.includes("event: done") });
  agentAnswer.add(res.timings.duration);
}

export function rbac(data) {
  check(http.get(`${BASE}/api/v1/stats/summary`), { "anonymous 401": (r) => r.status === 401 });
  const job = http.post(`${BASE}/api/v1/analysis/jobs`, JSON.stringify({ type: "REFRESH_RISK" }),
    { headers: { ...auth(data, "viewer"), "Content-Type": "application/json" } });
  check(job, { "viewer job 403": (r) => r.status === 403 });
  check(http.get(`${BASE}/api/v1/admin/audit`, { headers: auth(data, "analyst") }), { "analyst audit 403": (r) => r.status === 403 });
}
