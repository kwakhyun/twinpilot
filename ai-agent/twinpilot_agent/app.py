"""TwinPilot AI 에이전트 API(FastAPI).

POST /agent/chat → text/event-stream
  event: tool_call   {id, name, args}
  event: tool_result {id, name, ok, summary}
  event: ui_action   {type, ...}                 # 프론트엔드 지도 동작
  event: token       {text}                      # LLM 답변 스트리밍 조각
  event: message     {content}                   # 최종 답변
  event: done        {mode, model, sessionId}
  event: error       {message}
"""
from __future__ import annotations

import asyncio
import json
import logging
import uuid
from collections import defaultdict
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, ToolMessage
from langchain_mcp_adapters.client import MultiServerMCPClient
from langgraph.checkpoint.memory import InMemorySaver
from pydantic import BaseModel, Field

from .config import Settings
from .graph import build_graph
from .planner import RulePlanner, message_text

log = logging.getLogger("twinpilot.agent")


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=2000)
    session_id: str | None = Field(default=None, alias="sessionId", max_length=64)
    context: dict[str, Any] = Field(default_factory=dict)


def sse(event: str, data: Any) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def summarize_tool_result(msg: ToolMessage) -> str:
    head = message_text(msg.content).split("\n", 1)[0]
    return head[:200]


def chunk_text(chunk: AIMessageChunk) -> str:
    """답변 텍스트 조각만 꺼낸다(사고 블록·도구 입력 조각은 제외)."""
    if isinstance(chunk.content, str):
        return chunk.content
    return "".join(b.get("text", "") for b in chunk.content if isinstance(b, dict) and b.get("type") == "text")


class AgentService:
    """MCP 도구 로딩, 그래프 컴파일, 세션 직렬화를 담당한다."""

    def __init__(self, settings: Settings, tools=None, llm=None):
        self.settings = settings
        self.mode = settings.resolved_mode
        self.model_name = settings.model if self.mode == "llm" else RulePlanner.model_name
        self._tools = tools
        self._llm = llm
        self._graph = None
        self._init_lock = asyncio.Lock()
        self._session_locks: dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)
        self.checkpointer = None
        self._pool = None

    @property
    def ready(self) -> bool:
        return self._graph is not None

    async def _create_checkpointer(self):
        """CHECKPOINT_DB_URL이 있으면 PostgreSQL(레플리카 간 세션 공유), 없으면 메모리."""
        if not self.settings.checkpoint_db_url:
            return InMemorySaver()
        from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
        from psycopg_pool import AsyncConnectionPool

        self._pool = AsyncConnectionPool(self.settings.checkpoint_db_url, max_size=10, open=False,
                                         kwargs={"autocommit": True, "prepare_threshold": 0})
        await self._pool.open()
        saver = AsyncPostgresSaver(self._pool)
        await saver.setup()
        log.info("대화 상태 저장소: PostgreSQL")
        return saver

    async def aclose(self) -> None:
        if self._pool is not None:
            await self._pool.close()

    async def graph(self):
        async with self._init_lock:
            if self._graph is None:
                if self.checkpointer is None:
                    self.checkpointer = await self._create_checkpointer()
                tools = self._tools
                if tools is None:
                    client = MultiServerMCPClient({"twinpilot": {"transport": "streamable_http", "url": self.settings.mcp_url}})
                    tools = await client.get_tools()
                    log.info("MCP 도구 %d개 로드: %s", len(tools), [t.name for t in tools])
                if self.mode == "llm":
                    llm = self._llm
                    if llm is None:
                        from .llm import build_chat_model
                        llm = build_chat_model(self.settings)
                    self._graph = build_graph(tools, llm=llm, max_tool_rounds=self.settings.max_tool_rounds,
                                              checkpointer=self.checkpointer)
                else:
                    self._graph = build_graph(tools, planner=RulePlanner(), max_tool_rounds=self.settings.max_tool_rounds,
                                              checkpointer=self.checkpointer)
            return self._graph

    async def chat(self, req: ChatRequest) -> AsyncIterator[str]:
        session_id = req.session_id or uuid.uuid4().hex
        async with self._session_locks[session_id]:
            try:
                graph = await self.graph()
            except Exception as e:  # MCP 서버 미기동 등
                log.exception("그래프 초기화 실패")
                yield sse("error", {"message": f"분석 도구(MCP)에 연결하지 못했습니다: {e}"})
                return
            text = req.message
            if req.context.get("selectedBuildingId"):
                text += f"\n\n[화면 컨텍스트] 선택된 건물 ID: {int(req.context['selectedBuildingId'])}"
            inputs = {"messages": [HumanMessage(content=text)], "rounds": 0, "ui_actions": [],
                      "context": req.context}
            config = {"configurable": {"thread_id": session_id}, "recursion_limit": 40}
            streamed = False
            try:
                async for mode, payload in graph.astream(inputs, config, stream_mode=["updates", "messages"]):
                    if mode == "messages":
                        chunk, meta = payload
                        if meta.get("langgraph_node") == "agent" and isinstance(chunk, AIMessageChunk):
                            if piece := chunk_text(chunk):
                                streamed = True
                                yield sse("token", {"text": piece})
                        continue
                    for node, update in payload.items():
                        if not update:
                            continue
                        if node == "agent":
                            for m in update.get("messages", []):
                                for tc in getattr(m, "tool_calls", []) or []:
                                    yield sse("tool_call", {"id": tc["id"], "name": tc["name"], "args": tc["args"]})
                        elif node == "tools":
                            for m in update.get("messages", []):
                                if isinstance(m, ToolMessage):
                                    yield sse("tool_result", {"id": m.tool_call_id, "name": m.name,
                                                              "ok": m.status != "error", "summary": summarize_tool_result(m)})
                        elif node == "collect_ui":
                            for action in update.get("ui_actions", []):
                                yield sse("ui_action", action)
                state = await graph.aget_state(config)
                final = next((m for m in reversed(state.values["messages"]) if isinstance(m, AIMessage)), None)
                content = message_text(final.content) if final else ""
                yield sse("message", {"content": content, "streamed": streamed})
                yield sse("done", {"mode": self.mode, "model": self.model_name, "sessionId": session_id})
            except Exception as e:
                log.exception("대화 처리 실패")
                yield sse("error", {"message": f"처리 중 오류가 발생했습니다: {type(e).__name__}"})


def create_app(service: AgentService | None = None) -> FastAPI:
    service = service or AgentService(Settings())

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # MCP 서버가 늦게 뜰 수 있으므로 시작 시 실패해도 첫 요청에서 다시 시도한다.
        try:
            await service.graph()
        except Exception as e:
            log.warning("시작 시 MCP 도구 로드 실패(첫 요청에서 재시도): %s", e)
        yield
        await service.aclose()

    app = FastAPI(title="TwinPilot Agent", lifespan=lifespan)
    app.state.service = service

    @app.get("/agent/health")
    async def health() -> dict[str, Any]:
        return {"status": "UP", "mode": service.mode, "model": service.model_name, "toolsLoaded": service.ready}

    @app.post("/agent/chat")
    async def chat(req: ChatRequest) -> StreamingResponse:
        if not req.message.strip():
            raise HTTPException(400, "message is blank")
        return StreamingResponse(service.chat(req), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    return app


app = create_app()
