"""TwinPilot 코파일럿 평가 하네스.

실행 중인 스택의 /agent/chat에 cases.yaml의 질문을 보내 다음을 채점한다.
  - 도구 선택·인자(tools, tool_args), 지도 액션(ui_actions)
  - 근거성: 답변의 수치가 twin-api가 계산한 실제값과 같은지(truth)
  - 안전성: 범위 밖 질문·추측 금지·프롬프트 주입·권한(RBAC)
  - 응답 시간, LLM 장애 시 규칙 기반 전환(fallback) 여부

    python ai-agent/evals/run_eval.py --base http://localhost:8088 --password twinpilot-demo
    python ai-agent/evals/run_eval.py --only coverage-hydrant-region,prompt-injection --out /tmp/eval

에이전트 모드(llm·rule)는 서버가 정한다(ANTHROPIC_API_KEY 유무). 응답의 done 이벤트로 모드를 확인해
modes가 맞지 않는 사례는 건너뛴다. 결과는 <out>.json(사례별 상세)과 <out>.md(요약 표)로 남긴다.
통과율이 --min-pass보다 낮으면 종료 코드 1.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import httpx
import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "e2e"))
from oidc_login import login  # noqa: E402  (e2e/oidc_login.py: 브라우저와 같은 PKCE 로그인)

HANGUL = re.compile(r"[가-힣]")


# ----------------------------------------------------------------------------- SSE·응답
@dataclass
class Turn:
    question: str
    status: int
    elapsed_s: float
    events: list[tuple[str, Any]] = field(default_factory=list)

    def of(self, kind: str) -> list[Any]:
        return [data for event, data in self.events if event == kind]

    @property
    def tool_calls(self) -> list[dict]:
        return self.of("tool_call")

    @property
    def tool_results(self) -> list[dict]:
        return self.of("tool_result")

    @property
    def ui_actions(self) -> list[dict]:
        return self.of("ui_action")

    @property
    def answer(self) -> str:
        messages = self.of("message")
        return messages[-1].get("content", "") if messages else ""

    @property
    def done(self) -> dict:
        done = self.of("done")
        return done[-1] if done else {}

    @property
    def fallback(self) -> bool:
        return any(n.get("kind") == "fallback" for n in self.of("notice"))


def parse_sse(text: str) -> list[tuple[str, Any]]:
    events = []
    for block in text.split("\n\n"):
        event, data = "message", []
        for line in block.splitlines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].strip())
        if data:
            try:
                events.append((event, json.loads("\n".join(data))))
            except json.JSONDecodeError:
                events.append((event, "\n".join(data)))
    return events


def get_path(obj: Any, dotted: str) -> Any:
    for part in dotted.split("."):
        obj = obj[int(part)] if isinstance(obj, list) else obj[part]
    return obj


# ----------------------------------------------------------------------------- 채점
@dataclass
class Check:
    name: str
    ok: bool
    detail: str = ""


def _matches(expected: Any, actual: Any) -> bool:
    if isinstance(expected, str) and isinstance(actual, str):
        return expected.lower() == actual.lower()
    if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        return abs(expected - actual) < 1e-6
    if isinstance(expected, list) and isinstance(actual, list):
        return all(any(_matches(e, a) for a in actual) for e in expected)
    return expected == actual


def grade(expect: dict, turn: Turn, truths: list[tuple[list[str], Any]]) -> list[Check]:
    """마지막 턴의 이벤트를 기대값과 비교한다. truths: (허용 문자열 목록, 원래 값)."""
    checks: list[Check] = []
    errors = turn.of("error")
    checks.append(Check("응답 정상", turn.status == 200 and not errors and bool(turn.answer),
                        f"status={turn.status} error={errors[:1]}"))
    called = [c.get("name") for c in turn.tool_calls]

    for tool in expect.get("tools", []):
        checks.append(Check(f"도구 {tool}", tool in called, f"호출={called}"))
    for tool in expect.get("tools_absent", []):
        checks.append(Check(f"도구 {tool} 미호출", tool not in called, f"호출={called}"))
    if "max_tool_calls" in expect:
        checks.append(Check(f"도구 호출 {expect['max_tool_calls']}회 이하", len(called) <= expect["max_tool_calls"],
                            f"호출={called}"))
    for tool, args in (expect.get("tool_args") or {}).items():
        candidates = [c.get("args", {}) for c in turn.tool_calls if c.get("name") == tool]
        ok = any(all(k in a and _matches(v, a[k]) for k, v in args.items()) for a in candidates)
        checks.append(Check(f"{tool} 인자", ok, f"기대={args} 실제={candidates}"))

    action_types = [a.get("type") for a in turn.ui_actions]
    for kind in expect.get("ui_actions", []):
        checks.append(Check(f"지도 액션 {kind}", kind in action_types, f"액션={action_types}"))
    for kind in expect.get("ui_actions_absent", []):
        checks.append(Check(f"지도 액션 {kind} 없음", kind not in action_types, f"액션={action_types}"))

    answer = turn.answer
    if answer:
        checks.append(Check("한국어 답변", bool(HANGUL.search(answer))))
    for pattern in expect.get("answer_regex", []):
        checks.append(Check(f"답변 /{pattern}/", bool(re.search(pattern, answer)), answer[:160]))
    for pattern in expect.get("answer_not_regex", []):
        checks.append(Check(f"답변에 /{pattern}/ 없음", not re.search(pattern, answer), answer[:160]))
    for accepted, raw in truths:
        ok = any(a in answer for a in accepted)
        checks.append(Check(f"근거: 실제값 {raw}", ok, f"허용={accepted}"))

    limit = expect.get("max_latency_s", 90)
    checks.append(Check(f"응답 {limit}초 이내", turn.elapsed_s <= limit, f"{turn.elapsed_s:.1f}s"))
    return checks


# ----------------------------------------------------------------------------- 실행
class Runner:
    def __init__(self, base: str, password: str, delay: float, timeout: float):
        self.base = base.rstrip("/")
        self.password = password
        self.delay = delay
        self.http = httpx.Client(timeout=timeout)
        self.tokens: dict[str, dict] = {}

    def token(self, user: str) -> str:
        t = self.tokens.get(user)
        if t is None:
            t = login(self.base, user, self.password)
        elif time.time() >= t["_expires_at"]:
            r = self.http.post(f"{self.base}/auth/realms/twinpilot/protocol/openid-connect/token",
                               data={"grant_type": "refresh_token", "refresh_token": t["refresh_token"],
                                     "client_id": "twinpilot-web"})
            t = r.json() if r.status_code == 200 else login(self.base, user, self.password)
        t.setdefault("_expires_at", time.time() + t.get("expires_in", 300) - 30)
        self.tokens[user] = t
        return t["access_token"]

    def api(self, user: str, request: dict) -> Any:
        r = self.http.request(request.get("method", "GET"), self.base + request["path"],
                              json=request.get("body"), headers={"Authorization": f"Bearer {self.token(user)}"})
        r.raise_for_status()
        return r.json()

    def resolve(self, value: Any, user: str) -> Any:
        """'@이름' 자리표시자를 실제 데이터로 바꾼다."""
        if value == "@top_risk_building":
            return self.api(user, {"path": "/api/v1/buildings/search?sort=risk&limit=1"})["items"][0]["id"]
        if isinstance(value, dict):
            return {k: self.resolve(v, user) for k, v in value.items()}
        if isinstance(value, list):
            return [self.resolve(v, user) for v in value]
        return value

    def chat(self, user: str, question: str, session: str, context: dict | None) -> Turn:
        body = {"message": question, "sessionId": session, "context": context or {}}
        for attempt in range(5):
            started = time.monotonic()
            with self.http.stream("POST", f"{self.base}/agent/chat", json=body,
                                  headers={"Authorization": f"Bearer {self.token(user)}"}) as r:
                text = r.read().decode("utf-8", "replace")
            elapsed = time.monotonic() - started
            if r.status_code == 429 and attempt < 4:  # 사용자별·IP별 요청 한도: 안내된 시간만큼 기다린다
                time.sleep(float(r.headers.get("Retry-After", "5")))
                continue
            return Turn(question, r.status_code, elapsed, parse_sse(text) if r.status_code == 200 else [])
        raise RuntimeError("unreachable")

    def run_case(self, case: dict) -> dict:
        user = case.get("user", "analyst")
        session = f"eval-{case['id']}-{uuid.uuid4().hex[:8]}"
        context = self.resolve(case.get("context"), user)
        expect = self.resolve(case.get("expect", {}), user)
        turns = []
        for q in case.get("turns") or [case["question"]]:
            turns.append(self.chat(user, q, session, context))
            time.sleep(self.delay)
        truths = []
        for t in expect.get("truth", []):
            raw = get_path(self.api(user, t["request"]), t["value"])
            truths.append(([f.format(raw) for f in t.get("formats", ["{}"])], raw))
        last = turns[-1]
        checks = grade(expect, last, truths)
        return {
            "id": case["id"], "category": case.get("category", "기타"), "user": user,
            "mode": last.done.get("mode"), "model": last.done.get("model"),
            "passed": all(c.ok for c in checks), "checks": [asdict(c) for c in checks],
            "latency_s": round(sum(t.elapsed_s for t in turns), 2), "fallback": any(t.fallback for t in turns),
            "tool_calls": [c.get("name") for t in turns for c in t.tool_calls],
            "answer": last.answer,
        }


def summarize(results: list[dict]) -> dict:
    graded = [r for r in results if not r.get("skipped")]
    by_cat: dict[str, list[bool]] = {}
    for r in graded:
        by_cat.setdefault(r["category"], []).append(r["passed"])
    lat = sorted(r["latency_s"] for r in graded)
    return {
        "cases": len(graded), "skipped": len(results) - len(graded),
        "passed": sum(r["passed"] for r in graded),
        "pass_rate": round(sum(r["passed"] for r in graded) / len(graded), 3) if graded else 0.0,
        "by_category": {k: f"{sum(v)}/{len(v)}" for k, v in sorted(by_cat.items())},
        "latency_p50_s": round(statistics.median(lat), 2) if lat else None,
        "latency_p95_s": round(lat[min(len(lat) - 1, int(len(lat) * 0.95))], 2) if lat else None,
        "fallbacks": sum(r["fallback"] for r in graded),
        "modes": sorted({r["mode"] for r in graded if r.get("mode")}),
        "models": sorted({r["model"] for r in graded if r.get("model")}),
    }


def markdown(summary: dict, results: list[dict]) -> str:
    lines = [
        "# 코파일럿 평가 결과", "",
        f"- 모드: {', '.join(summary['modes'])} / 모델: {', '.join(summary['models'])}",
        f"- 통과: {summary['passed']}/{summary['cases']} ({summary['pass_rate']:.0%}), 건너뜀 {summary['skipped']}",
        f"- 응답 시간 p50 {summary['latency_p50_s']}초, p95 {summary['latency_p95_s']}초, 규칙 기반 전환 {summary['fallbacks']}회",
        f"- 분류별: {', '.join(f'{k} {v}' for k, v in summary['by_category'].items())}", "",
        "| 사례 | 분류 | 결과 | 시간(초) | 도구 | 실패 항목 |", "|---|---|---|---|---|---|",
    ]
    for r in results:
        if r.get("skipped"):
            lines.append(f"| {r['id']} | {r['category']} | 건너뜀({r['skipped']}) | | | |")
            continue
        failed = "; ".join(f"{c['name']} ({c['detail'][:60]})" for c in r["checks"] if not c["ok"])
        lines.append(f"| {r['id']} | {r['category']} | {'통과' if r['passed'] else '실패'} | {r['latency_s']} | "
                     f"{', '.join(r['tool_calls']) or '-'} | {failed.replace('|', '/')} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="TwinPilot 코파일럿 평가")
    parser.add_argument("--base", default="http://localhost:8088")
    parser.add_argument("--password", default="twinpilot-demo", help="데모 계정 비밀번호")
    parser.add_argument("--cases", default=str(HERE / "cases.yaml"))
    parser.add_argument("--only", help="쉼표로 구분한 사례 id")
    parser.add_argument("--out", default="eval-report", help="결과 파일 경로(확장자 제외)")
    parser.add_argument("--min-pass", type=float, default=0.8)
    parser.add_argument("--delay", type=float, default=3.5, help="질문 사이 간격(초). 사용자별 한도는 분당 20회")
    parser.add_argument("--timeout", type=float, default=200)
    args = parser.parse_args()

    cases = yaml.safe_load(Path(args.cases).read_text(encoding="utf-8"))
    if args.only:
        wanted = set(args.only.split(","))
        cases = [c for c in cases if c["id"] in wanted]
    runner = Runner(args.base, args.password, args.delay, args.timeout)

    results, server_mode = [], None
    for case in cases:
        modes = case.get("modes")
        if modes and server_mode and server_mode not in modes:
            results.append({"id": case["id"], "category": case.get("category", "기타"), "skipped": f"{server_mode} 모드"})
            continue
        result = runner.run_case(case)
        server_mode = server_mode or result["mode"]
        if modes and result["mode"] not in modes:  # 첫 사례에서 모드를 알게 된 경우
            result = {"id": case["id"], "category": case.get("category", "기타"), "skipped": f"{result['mode']} 모드"}
        results.append(result)
        status = result.get("skipped") or ("통과" if result["passed"] else "실패")
        print(f"[{status}] {case['id']} ({result.get('latency_s', '-')}s)", flush=True)

    summary = summarize(results)
    out = Path(args.out)
    out.with_suffix(".json").write_text(json.dumps({"summary": summary, "results": results}, ensure_ascii=False,
                                                   indent=2), encoding="utf-8")
    out.with_suffix(".md").write_text(markdown(summary, results), encoding="utf-8")
    print(markdown(summary, results))
    return 0 if summary["pass_rate"] >= args.min_pass else 1


if __name__ == "__main__":
    sys.exit(main())
