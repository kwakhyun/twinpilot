"""도구 선택 평가셋: 질문 → 기대 도구·핵심 인자. 플래너 회귀 테스트이자 LLM 프롬프트 튜닝용 기준선."""
import pytest
from langchain_core.messages import HumanMessage, ToolMessage

from twinpilot_agent.planner import HELP, RulePlanner, plan

CASES = [
    ("다솜동에서 소화전 사각지대에 있는 30년 이상 노후 건물을 위험도순으로 보여줘",
     [("search_buildings", {"region": "다솜동", "min_age": 30, "uncovered_by": "HYDRANT", "sort": "risk"})]),
    ("한빛동에 AED 3대를 추가한다면 어디가 좋을까?",
     [("recommend_facility_sites", {"facility_type": "AED", "count": 3, "region": "한빛동"})]),
    ("소화전 2개를 더 설치하면 어디가 좋아?",
     [("recommend_facility_sites", {"facility_type": "HYDRANT", "count": 2})]),
    ("누리동 소화전 커버리지는?", [("analyze_coverage", {"facility_type": "HYDRANT", "region": "누리동"})]),
    ("AED 반경 500m로 커버리지 분석해줘", [("analyze_coverage", {"facility_type": "AED", "radius_m": 500})]),
    ("소방서 골든타임 도달 현황 알려줘", [("analyze_response_time", {})]),
    ("한빛동 출동 시간 5분 안에 되나?", [("analyze_response_time", {"region": "한빛동", "target_min": 5})]),
    ("소방차가 4분 안에 도착하지 못하는 건물은 어디야?", [("analyze_response_time", {"target_min": 4})]),
    ("지금 수압 경보가 난 소화전이 있어?",
     [("get_sensor_status", {"kind": "HYDRANT_PRESSURE", "alerts_only": True})]),
    ("버스 미세먼지 센서 값 보여줘", [("get_sensor_status", {"kind": "AIR_QUALITY", "alerts_only": False})]),
    ("행정동별 화재 위험 현황을 요약해줘", [("get_dashboard_stats", {})]),
    ("건물 696번 화재 대응 브리핑을 해줘", [("get_building_detail", {"building_id": 696})]),
    ("가람동 20층 이상 고층 건물", [("search_buildings", {"region": "가람동", "min_floors": 20})]),
    ("업로드된 3D 모델 목록", [("list_3d_models", {})]),
    ("다솜동 소화전 커버리지랑 수압 경보 같이 알려줘",
     [("analyze_coverage", {"facility_type": "HYDRANT", "region": "다솜동"}),
      ("get_sensor_status", {"kind": "HYDRANT_PRESSURE", "alerts_only": True})]),
]


@pytest.mark.parametrize("question,expected", CASES, ids=[c[0][:20] for c in CASES])
def test_tool_selection(question, expected):
    intents = plan(question)
    assert [i.tool for i in intents] == [e[0] for e in expected]
    for intent, (_, args) in zip(intents, expected):
        for k, v in args.items():
            assert intent.args.get(k) == v, f"{k}: {intent.args}"


def test_tool_selection_accuracy_report():
    """평가셋 정확도(도구 이름 기준). 계획서 목표 90% 이상."""
    hits = sum([i.tool for i in plan(q)] == [e[0] for e in exp] for q, exp in CASES)
    assert hits / len(CASES) >= 0.9


def test_selected_building_context():
    intents = plan("이 건물 브리핑해줘", {"selectedBuildingId": 42})
    assert intents[0].tool == "get_building_detail" and intents[0].args == {"building_id": 42}


def test_not_a_region():
    assert "region" not in plan("소방차 출동 도달 시간 커버리지")[0].args


def test_unknown_question_returns_help_without_tools():
    msg = RulePlanner()([HumanMessage(content="오늘 날씨 어때?")])
    assert not msg.tool_calls and msg.content == HELP


def test_answer_composed_from_tool_results_only():
    planner = RulePlanner()
    call = planner([HumanMessage(content="누리동 소화전 커버리지는?")])
    result = ToolMessage(
        content='소화전 커버리지 97.4% (누리동, 미커버 6동, 미커버 고위험 0동)\n'
                '{"standard": "법정", "coverageRate": 97.4, "totalBuildings": 229, "uncovered": 6, '
                '"uncoveredHighRisk": 0, "regions": [{"name": "누리동", "coverageRate": 97.4, "radiusM": 100, '
                '"uncoveredHighRisk": 0}], "topUncovered": []}',
        tool_call_id=call.tool_calls[0]["id"], name="analyze_coverage")
    answer = planner([HumanMessage(content="누리동 소화전 커버리지는?"), call, result])
    assert "97.4%" in answer.content and "합성(가상)" in answer.content and not answer.tool_calls


def test_screen_context_suffix_is_not_part_of_the_question():
    from langchain_core.messages import HumanMessage

    from twinpilot_agent.planner import CONTEXT_MARK, RulePlanner
    msg = HumanMessage(content="소방차가 4분 안에 도착하지 못하는 건물은 어디야?" + CONTEXT_MARK + " 선택된 건물 ID: 1516")
    ai = RulePlanner()([msg], {"selectedBuildingId": 1516})
    assert [tc["name"] for tc in ai.tool_calls] == ["analyze_response_time"]
    # 사용자가 직접 '이 건물'이라고 하면 선택 건물을 쓴다.
    ai = RulePlanner()([HumanMessage(content="이 건물 브리핑해줘" + CONTEXT_MARK + " 선택된 건물 ID: 7")], {"selectedBuildingId": 7})
    assert ai.tool_calls[0]["name"] == "get_building_detail" and ai.tool_calls[0]["args"] == {"building_id": 7}
