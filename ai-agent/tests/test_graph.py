"""그래프·SSE 테스트: 가짜 LLM(도구 호출을 내는 스크립트)과 가짜 MCP 도구로 LLM 경로 전체를 검증한다."""
import json
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import StructuredTool

from twinpilot_agent.app import AgentService, ChatRequest
from twinpilot_agent.config import Settings
from twinpilot_agent.graph import build_graph, extract_ui_actions


class ScriptedModel(BaseChatModel):
    """미리 정한 응답을 차례로 돌려주는 채팅 모델. 받은 입력을 기록한다."""

    responses: list[AIMessage]
    seen: list[list[BaseMessage]] = []

    @property
    def _llm_type(self) -> str:
        return "scripted"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs) -> ChatResult:
        self.seen.append(list(messages))
        return ChatResult(generations=[ChatGeneration(message=self.responses.pop(0))])


def coverage_tool(calls: list[dict[str, Any]]):
    async def analyze_coverage(facility_type: str, region: str | None = None):
        calls.append({"facility_type": facility_type, "region": region})
        text = f"소화전 커버리지 89.7% ({region})\n" + json.dumps({"coverageRate": 89.7})
        return text, {"structured_content": {"data": {}, "ui_actions": [
            {"type": "runCoverage", "facilityType": facility_type, "regionId": 3},
            {"type": "highlightBuildings", "ids": [1, 2, 3], "label": "미커버"}]}}

    return StructuredTool.from_function(coroutine=analyze_coverage, name="analyze_coverage",
                                        description="coverage", response_format="content_and_artifact")


def failing_tool():
    async def get_sensor_status(alerts_only: bool = True):
        raise RuntimeError("센서 서버 응답 없음")

    return StructuredTool.from_function(coroutine=get_sensor_status, name="get_sensor_status", description="sensors")


def scripted(*responses: AIMessage) -> ScriptedModel:
    return ScriptedModel(responses=list(responses), seen=[])


async def collect(service: AgentService, message: str, session: str = "s1") -> list[tuple[str, Any]]:
    events = []
    async for raw in service.chat(ChatRequest(message=message, sessionId=session)):
        head, data = raw.strip().split("\n", 1)
        events.append((head.removeprefix("event: "), json.loads(data.removeprefix("data: "))))
    return events


def llm_service(model, tools) -> AgentService:
    settings = Settings(mode="llm", max_tool_rounds=2)
    return AgentService(settings, tools=tools, llm=model)


async def test_llm_path_streams_tool_calls_ui_actions_and_answer():
    calls: list[dict] = []
    model = scripted(
        AIMessage(content="", tool_calls=[{"name": "analyze_coverage", "args": {"facility_type": "HYDRANT", "region": "다솜동"},
                                           "id": "t1", "type": "tool_call"}]),
        AIMessage(content="다솜동 소화전 커버리지는 89.7%입니다."),
    )
    events = await collect(llm_service(model, [coverage_tool(calls)]), "다솜동 소화전 커버리지는?")

    names = [e for e, _ in events]
    assert names == ["tool_call", "tool_result", "ui_action", "ui_action", "message", "done"]
    assert events[0][1]["args"] == {"facility_type": "HYDRANT", "region": "다솜동"}
    assert events[1][1] == {"id": "t1", "name": "analyze_coverage", "ok": True, "summary": "소화전 커버리지 89.7% (다솜동)"}
    assert events[3][1]["ids"] == [1, 2, 3]
    assert events[4][1]["content"] == "다솜동 소화전 커버리지는 89.7%입니다."
    assert events[5][1] == {"mode": "llm", "model": "claude-opus-5-5", "sessionId": "s1"}
    assert calls == [{"facility_type": "HYDRANT", "region": "다솜동"}]
    # 두 번째 LLM 호출은 시스템 프롬프트(캐시 지점) + 사용자 + 도구 호출 + 도구 결과를 본다.
    second = model.seen[1]
    assert second[0].type == "system" and second[0].content[0]["cache_control"] == {"type": "ephemeral"}
    assert isinstance(second[-1], ToolMessage) and second[-1].artifact is not None


async def test_session_history_is_append_only_across_turns():
    model = scripted(AIMessage(content="첫 답"), AIMessage(content="둘째 답"))
    service = llm_service(model, [coverage_tool([])])
    await collect(service, "안녕", session="abc")
    await collect(service, "다시 질문", session="abc")
    second_input = model.seen[1]
    assert [m.type for m in second_input] == ["system", "human", "ai", "human"]
    assert second_input[2].content == "첫 답"


async def test_tool_errors_are_reported_not_raised():
    model = scripted(
        AIMessage(content="", tool_calls=[{"name": "get_sensor_status", "args": {}, "id": "t9", "type": "tool_call"}]),
        AIMessage(content="센서 조회에 실패했습니다."),
    )
    events = await collect(llm_service(model, [failing_tool()]), "센서 경보?")
    result = dict(events)["tool_result"]
    assert result["ok"] is False and "센서 서버 응답 없음" in result["summary"]
    assert dict(events)["message"]["content"] == "센서 조회에 실패했습니다."


async def test_tool_round_limit_finalizes_with_valid_history():
    loop_call = lambda i: AIMessage(content="", tool_calls=[  # noqa: E731
        {"name": "analyze_coverage", "args": {"facility_type": "HYDRANT"}, "id": f"t{i}", "type": "tool_call"}])
    model = scripted(loop_call(1), loop_call(2), loop_call(3))
    service = llm_service(model, [coverage_tool([])])  # max_tool_rounds=2
    events = await collect(service, "계속 분석해")
    assert "분석 단계가 많아" in dict(events)["message"]["content"]
    state = await (await service.graph()).aget_state({"configurable": {"thread_id": "s1"}})
    # 모든 tool_use에 대응하는 tool_result가 있어야 다음 턴 요청이 유효하다.
    msgs = state.values["messages"]
    pending = {tc["id"] for m in msgs if isinstance(m, AIMessage) for tc in m.tool_calls}
    answered = {m.tool_call_id for m in msgs if isinstance(m, ToolMessage)}
    assert pending == answered


async def test_rule_mode_uses_same_graph():
    calls: list[dict] = []
    service = AgentService(Settings(mode="rule"), tools=[coverage_tool(calls)])
    events = await collect(service, "다솜동 소화전 커버리지는?")
    assert [e for e, _ in events][:2] == ["tool_call", "tool_result"]
    assert dict(events)["done"]["mode"] == "rule"
    assert "89.7%" in dict(events)["message"]["content"]


def test_extract_ui_actions_only_after_last_ai_message():
    old = ToolMessage(content="x", tool_call_id="a", artifact={"structured_content": {"ui_actions": [{"type": "flyTo"}]}})
    new = ToolMessage(content="y", tool_call_id="b",
                      artifact={"structured_content": {"ui_actions": [{"type": "selectBuilding", "id": 1}, {"bad": 1}]}})
    assert extract_ui_actions([old, AIMessage(content="", tool_calls=[]), new]) == [{"type": "selectBuilding", "id": 1}]


def test_build_graph_requires_exactly_one_decider():
    import pytest
    with pytest.raises(ValueError):
        build_graph([], llm=None, planner=None)


async def test_postgres_checkpointer_shares_sessions_across_instances():
    """CHECKPOINT_DB_URL이 있으면 다른 인스턴스(레플리카)에서도 같은 세션의 대화가 이어진다."""
    import os
    import uuid

    import pytest
    url = os.getenv("TEST_CHECKPOINT_DB_URL")
    if not url:
        pytest.skip("TEST_CHECKPOINT_DB_URL 필요")
    session = uuid.uuid4().hex
    first = AgentService(Settings(mode="llm", checkpoint_db_url=url), tools=[coverage_tool([])],
                         llm=scripted(AIMessage(content="첫 답")))
    await collect(first, "안녕", session=session)
    await first.aclose()
    model = scripted(AIMessage(content="둘째 답"))
    second = AgentService(Settings(mode="llm", checkpoint_db_url=url), tools=[coverage_tool([])], llm=model)
    await collect(second, "이어서", session=session)
    await second.aclose()
    assert [m.type for m in model.seen[0]] == ["system", "human", "ai", "human"]
