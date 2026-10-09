"""LangGraph 에이전트 그래프.

  START → agent ─(tool_calls)→ tools → collect_ui → agent → … → END
                └(한도 초과)→ finalize → END

- agent: LLM(도구 바인딩) 또는 규칙 기반 플래너
- tools: MCP 도구를 감싼 LangChain Tool. 한 턴의 여러 호출을 동시에 실행한다
- collect_ui: 도구 결과(structuredContent)의 ui_actions를 모아 프론트엔드로 흘려보낸다
- finalize: 도구 호출 한도에 걸리면 미완료 호출을 정리해 대화 기록을 유효하게 유지한다
대화 기록은 체크포인터에 thread_id(세션)별로 이어 붙이기만 한다(append-only).
"""
from __future__ import annotations

from typing import Annotated, Any, Callable, TypedDict

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, AnyMessage, SystemMessage, ToolMessage
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import BaseTool
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode

from .llm import SYSTEM_PROMPT
from .planner import RulePlanner


class AgentState(TypedDict, total=False):
    messages: Annotated[list[AnyMessage], add_messages]
    ui_actions: list[dict[str, Any]]
    rounds: int
    context: dict[str, Any]


def extract_ui_actions(messages: list[AnyMessage]) -> list[dict[str, Any]]:
    """마지막 AI 메시지 이후의 도구 결과에서 지도 액션을 꺼낸다."""
    actions: list[dict[str, Any]] = []
    for m in reversed(messages):
        if isinstance(m, AIMessage):
            break
        if isinstance(m, ToolMessage) and isinstance(m.artifact, dict):
            structured = m.artifact.get("structured_content") or {}
            actions[:0] = [a for a in structured.get("ui_actions", []) if isinstance(a, dict) and "type" in a]
    return actions


def build_graph(
    tools: list[BaseTool],
    *,
    llm: BaseChatModel | None = None,
    planner: Callable[..., AIMessage] | None = None,
    max_tool_rounds: int = 5,
    checkpointer: BaseCheckpointSaver | None = None,
):
    if (llm is None) == (planner is None):
        raise ValueError("llm 또는 planner 중 하나만 지정해야 합니다.")
    bound = llm.bind_tools(tools) if llm is not None else None
    # 고정된 시스템 프롬프트는 캐시 지점으로 표시해 반복 요청 비용을 줄인다.
    system = SystemMessage(content=[{"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}}])

    async def agent(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        if bound is not None:
            response = await bound.ainvoke([system, *state["messages"]], config)
        else:
            response = planner(state["messages"], state.get("context"))
        rounds = state.get("rounds", 0) + (1 if response.tool_calls else 0)
        return {"messages": [response], "rounds": rounds}

    def route_after_agent(state: AgentState) -> str:
        last = state["messages"][-1]
        if isinstance(last, AIMessage) and last.tool_calls:
            return "tools" if state.get("rounds", 0) <= max_tool_rounds else "finalize"
        return END

    def collect_ui(state: AgentState) -> dict[str, Any]:
        return {"ui_actions": extract_ui_actions(state["messages"])}

    def finalize(state: AgentState) -> dict[str, Any]:
        last = state["messages"][-1]
        skipped = [ToolMessage(content="도구 호출 한도에 도달해 실행하지 않았습니다.", tool_call_id=tc["id"],
                               name=tc["name"], status="error") for tc in last.tool_calls]
        note = AIMessage(content="분석 단계가 많아 여기서 멈췄습니다. 질문을 나눠서 다시 요청해 주세요.")
        return {"messages": [*skipped, note]}

    graph = StateGraph(AgentState)
    graph.add_node("agent", agent)
    graph.add_node("tools", ToolNode(tools, handle_tool_errors=True))
    graph.add_node("collect_ui", collect_ui)
    graph.add_node("finalize", finalize)
    graph.add_edge(START, "agent")
    graph.add_conditional_edges("agent", route_after_agent, ["tools", "finalize", END])
    graph.add_edge("tools", "collect_ui")
    graph.add_edge("collect_ui", "agent")
    graph.add_edge("finalize", END)
    return graph.compile(checkpointer=checkpointer)


def default_planner() -> RulePlanner:
    return RulePlanner()
