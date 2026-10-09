"""평가 하네스(evals/run_eval.py)의 SSE 해석·채점 로직 검증(네트워크 없음)."""
import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("run_eval", Path(__file__).parents[1] / "evals" / "run_eval.py")
run_eval = importlib.util.module_from_spec(spec)
sys.modules["run_eval"] = run_eval  # dataclass가 모듈을 찾을 수 있게 등록한다
spec.loader.exec_module(run_eval)

STREAM = (
    'event: tool_call\ndata: {"id": "t1", "name": "analyze_coverage", "args": {"facility_type": "hydrant", "region": "다솜동"}}\n\n'
    'event: tool_result\ndata: {"id": "t1", "name": "analyze_coverage", "ok": true, "summary": "커버리지 89.7%"}\n\n'
    'event: ui_action\ndata: {"type": "runCoverage", "facilityType": "HYDRANT"}\n\n'
    'event: message\ndata: {"content": "다솜동 소화전 커버리지는 89.7%입니다.", "streamed": false}\n\n'
    'event: done\ndata: {"mode": "llm", "model": "claude-opus-5-5", "sessionId": "s"}\n\n'
)


def turn(text=STREAM, status=200, elapsed=1.0):
    return run_eval.Turn("q", status, elapsed, run_eval.parse_sse(text))


def failed(checks):
    return [c.name for c in checks if not c.ok]


def test_parse_sse_extracts_events():
    t = turn()
    assert [c["name"] for c in t.tool_calls] == ["analyze_coverage"]
    assert t.answer.startswith("다솜동")
    assert t.done["mode"] == "llm" and not t.fallback


def test_grade_passes_matching_expectation_with_grounding():
    expect = {"tools": ["analyze_coverage"], "tool_args": {"analyze_coverage": {"facility_type": "HYDRANT"}},
              "ui_actions": ["runCoverage"], "answer_regex": ["다솜동"]}
    checks = run_eval.grade(expect, turn(), truths=[(["89.7", "90"], 89.7)])
    assert failed(checks) == []


def test_grade_flags_wrong_tool_ungrounded_number_and_leak():
    expect = {"tools": ["search_buildings"], "answer_not_regex": ["커버리지"], "max_latency_s": 0.5}
    checks = run_eval.grade(expect, turn(), truths=[(["91.2"], 91.2)])
    assert failed(checks) == ["도구 search_buildings", "답변에 /커버리지/ 없음", "근거: 실제값 91.2", "응답 0.5초 이내"]


def test_grade_fails_on_error_event_or_http_error():
    assert "응답 정상" in failed(run_eval.grade({}, turn('event: error\ndata: {"message": "x"}\n\n'), []))
    assert "응답 정상" in failed(run_eval.grade({}, turn("", status=429), []))


def test_get_path_and_summary():
    assert run_eval.get_path({"items": [{"name": "a"}]}, "items.0.name") == "a"
    results = [{"category": "c", "passed": True, "latency_s": 1.0, "fallback": False, "mode": "rule", "model": "m"},
               {"category": "c", "passed": False, "latency_s": 3.0, "fallback": True, "mode": "rule", "model": "m"},
               {"category": "d", "skipped": "rule 모드"}]
    s = run_eval.summarize(results)
    assert (s["cases"], s["skipped"], s["passed"], s["pass_rate"], s["fallbacks"]) == (2, 1, 1, 0.5, 1)
    assert s["by_category"] == {"c": "1/2"}
