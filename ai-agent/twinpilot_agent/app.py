"""TwinPilot AI 에이전트 API(FastAPI).

POST /agent/chat → text/event-stream
  event: tool_call   {id, name, args}
  event: tool_result {id, name, ok, summary}
  event: ui_action   {type, ...}                 # 프론트엔드 지도 동작
  event: token       {text}                      # LLM 답변 스트리밍 조각
  event: notice      {kind, message}             # 예: LLM 장애로 규칙 기반 응답 전환(fallback)
  event: message     {content, streamed}         # 최종 답변
  event: done        {mode, model, sessionId}
  event: error       {message}
GET /agent/sessions/{id}/messages → 대화 기록(새로고침 후 복원)
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from collections import defaultdict
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from fastapi import Depends, FastAPI, HTTPException, Path, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, ToolMessage
from langchain_mcp_adapters.client import MultiServerMCPClient
from langgraph.checkpoint.memory import InMemorySaver
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, Field

from .auth import DEV_USER, AuthConfig, AuthError, Authenticator, McpAuth, User, mcp_token
from .config import Settings
from .graph import build_graph
from .planner import RulePlanner, message_text
from .ratelimit import RateLimiter
from .telemetry import (CHAT_LATENCY, CHAT_REQUESTS, LLM_FALLBACKS, LLM_TOKENS, RATE_LIMITED, TOOL_CALLS,
                        setup_logging, setup_tracing)

log = logging.getLogger("twinpilot.agent")
SESSION_PATTERN = r"^[A-Za-z0-9_-]{1,64}$"
CONTEXT_MARK = "\n\n[화면 컨텍스트]"


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=2000)
    session_id: str | None = Field(default=None, alias="sessionId", pattern=SESSION_PATTERN)
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


def is_llm_outage(e: BaseException) -> str | None:
    """재시도 후에도 실패한 LLM API 오류(과부하·속도 제한·서버 오류·연결·시간 초과)면 사유를 돌려준다."""
    try:
        import anthropic
    except ImportError:   # pragma: no cover
        return None
    if isinstance(e, anthropic.APITimeoutError):
        return "timeout"
    if isinstance(e, anthropic.APIConnectionError):
        return "connection"
    if isinstance(e, anthropic.APIStatusError) and (e.status_code == 429 or e.status_code >= 500):
        return f"status_{e.status_code}"
    return None


class AgentService:
    """MCP 도구 로딩, 그래프 컴파일, 사용자·세션별 직렬화를 담당한다."""

    def __init__(self, settings: Settings, tools=None, llm=None, authenticator: Authenticator | None = None):
        self.settings = settings
        self.mode = settings.resolved_mode
        self.model_name = settings.model if self.mode == "llm" else RulePlanner.model_name
        self.authenticator = authenticator
        self._tools = tools
        self._llm = llm
        self._graph = None
        self._rule_graph = None
        self._init_lock = asyncio.Lock()
        self._session_locks: dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)
        self.checkpointer = None
        self._pool = None
        self._cleanup_task: asyncio.Task | None = None

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
        if self._cleanup_task is not None:
            self._cleanup_task.cancel()
        if self._pool is not None:
            await self._pool.close()
        if self.authenticator is not None:
            await self.authenticator.aclose()

    async def graph(self):
        async with self._init_lock:
            if self._graph is None:
                if self.checkpointer is None:
                    self.checkpointer = await self._create_checkpointer()
                tools = self._tools
                if tools is None:
                    connection: dict[str, Any] = {"transport": "streamable_http", "url": self.settings.mcp_url}
                    if self.authenticator is not None:
                        connection["auth"] = McpAuth(self.authenticator)
                    tools = await MultiServerMCPClient({"twinpilot": connection}).get_tools()
                    log.info("MCP 도구 %d개 로드: %s", len(tools), [t.name for t in tools])
                rounds = self.settings.max_tool_rounds
                # 규칙 기반 그래프는 같은 체크포인터를 써서 LLM 장애 시 같은 턴을 이어받는다.
                self._rule_graph = build_graph(tools, planner=RulePlanner(), max_tool_rounds=rounds,
                                               checkpointer=self.checkpointer)
                if self.mode == "llm":
                    llm = self._llm
                    if llm is None:
                        from .llm import build_chat_model
                        llm = build_chat_model(self.settings)
                    self._graph = build_graph(tools, llm=llm, max_tool_rounds=rounds, checkpointer=self.checkpointer)
                else:
                    self._graph = self._rule_graph
                if self._pool is not None and self._cleanup_task is None:
                    self._cleanup_task = asyncio.create_task(self._cleanup_loop())
            return self._graph

    @staticmethod
    def thread_id(user: User, session_id: str) -> str:
        """대화 기록은 사용자별로 격리한다(다른 사용자의 세션 ID를 알아도 읽을 수 없다)."""
        return f"{user.subject}:{session_id}"

    async def chat(self, req: ChatRequest, user: User = DEV_USER) -> AsyncIterator[str]:
        session_id = req.session_id or uuid.uuid4().hex
        thread = self.thread_id(user, session_id)
        started = time.perf_counter()
        mode = self.mode
        outcome = "ok"
        async with self._session_locks[thread]:
            try:
                if self.authenticator is not None:
                    mcp_token.set(await self.authenticator.mcp_token_for(user))
                graph = await self.graph()
            except Exception as e:  # MCP 서버 미기동, 토큰 교환 실패 등
                log.exception("그래프 초기화 실패")
                CHAT_REQUESTS.labels(mode, "init_error").inc()
                yield sse("error", {"message": f"분석 도구(MCP)에 연결하지 못했습니다: {type(e).__name__}"})
                return
            text = req.message
            if req.context.get("selectedBuildingId"):
                text += f"{CONTEXT_MARK} 선택된 건물 ID: {int(req.context['selectedBuildingId'])}"
            inputs: dict[str, Any] | None = {"messages": [HumanMessage(content=text)], "rounds": 0, "ui_actions": [],
                                             "context": req.context}
            config = {"configurable": {"thread_id": thread}, "recursion_limit": 40}
            streamed = False
            try:
                async with asyncio.timeout(self.settings.request_timeout_s):
                    try:
                        async for event in self._run(graph, inputs, config):
                            if event.startswith("event: token"):
                                streamed = True
                            yield event
                    except Exception as e:
                        reason = is_llm_outage(e) if graph is not self._rule_graph else None
                        if reason is None or not self.settings.llm_fallback:
                            raise
                        # LLM이 재시도 후에도 응답하지 못하면 마지막 체크포인트에서 규칙 기반 그래프로 이어간다.
                        log.warning("LLM 장애(%s): 규칙 기반 응답으로 전환", reason)
                        LLM_FALLBACKS.labels(reason).inc()
                        mode = "rule-fallback"
                        streamed = False
                        yield sse("notice", {"kind": "fallback",
                                             "message": "AI 모델 응답이 지연돼 규칙 기반 분석으로 답합니다."})
                        async for event in self._run(self._rule_graph, None, config):
                            yield event
                    state = await graph.aget_state(config)
                    final = next((m for m in reversed(state.values["messages"]) if isinstance(m, AIMessage)), None)
                    content = message_text(final.content) if final else ""
                    yield sse("message", {"content": content, "streamed": streamed})
                    yield sse("done", {"mode": mode, "model": self.model_name if mode == "llm" else RulePlanner.model_name,
                                       "sessionId": session_id})
            except TimeoutError:
                outcome = "timeout"
                yield sse("error", {"message": "응답 시간이 초과됐습니다. 질문을 나눠서 다시 시도해 주세요."})
            except Exception as e:
                outcome = "error"
                log.exception("대화 처리 실패")
                yield sse("error", {"message": f"처리 중 오류가 발생했습니다: {type(e).__name__}"})
            finally:
                CHAT_REQUESTS.labels(mode, outcome).inc()
                CHAT_LATENCY.labels(mode).observe(time.perf_counter() - started)

    async def _run(self, graph, inputs, config) -> AsyncIterator[str]:
        async for stream_mode, payload in graph.astream(inputs, config, stream_mode=["updates", "messages"]):
            if stream_mode == "messages":
                chunk, meta = payload
                if meta.get("langgraph_node") == "agent" and isinstance(chunk, AIMessageChunk):
                    if piece := chunk_text(chunk):
                        yield sse("token", {"text": piece})
                continue
            for node, update in payload.items():
                if not update:
                    continue
                if node == "agent":
                    for m in update.get("messages", []):
                        self._count_usage(m)
                        for tc in getattr(m, "tool_calls", []) or []:
                            yield sse("tool_call", {"id": tc["id"], "name": tc["name"], "args": tc["args"]})
                elif node == "tools":
                    for m in update.get("messages", []):
                        if isinstance(m, ToolMessage):
                            ok = m.status != "error"
                            TOOL_CALLS.labels(m.name or "unknown", str(ok).lower()).inc()
                            yield sse("tool_result", {"id": m.tool_call_id, "name": m.name, "ok": ok,
                                                      "summary": summarize_tool_result(m)})
                elif node == "collect_ui":
                    for action in update.get("ui_actions", []):
                        yield sse("ui_action", action)

    def _count_usage(self, message: Any) -> None:
        usage = getattr(message, "usage_metadata", None)
        if not usage:
            return
        LLM_TOKENS.labels(self.model_name, "input").inc(usage.get("input_tokens", 0))
        LLM_TOKENS.labels(self.model_name, "output").inc(usage.get("output_tokens", 0))
        details = usage.get("input_token_details") or {}
        if details.get("cache_read"):
            LLM_TOKENS.labels(self.model_name, "cache_read").inc(details["cache_read"])

    async def history(self, user: User, session_id: str) -> list[dict[str, Any]]:
        graph = await self.graph()
        state = await graph.aget_state({"configurable": {"thread_id": self.thread_id(user, session_id)}})
        out: list[dict[str, Any]] = []
        for m in (state.values or {}).get("messages", []):
            if isinstance(m, HumanMessage):
                out.append({"role": "user", "content": message_text(m.content).split(CONTEXT_MARK, 1)[0]})
            elif isinstance(m, ToolMessage):
                out.append({"role": "tool", "name": m.name, "ok": m.status != "error",
                            "summary": summarize_tool_result(m)})
            elif isinstance(m, AIMessage) and (text := message_text(m.content).strip()):
                out.append({"role": "assistant", "content": text})
        return out

    async def _cleanup_loop(self) -> None:
        """보존 기간이 지난 대화(PostgreSQL 체크포인트)를 하루 한 번 지운다. 여러 레플리카가 실행해도 안전하다."""
        while True:
            try:
                await asyncio.sleep(3600)
                async with self._pool.connection() as conn:
                    async with conn.cursor() as cur:
                        await cur.execute("SELECT pg_try_advisory_lock(7420010)")
                        if not (await cur.fetchone())[0]:
                            continue
                        try:
                            await cur.execute("""
                                WITH old AS (
                                    SELECT thread_id FROM checkpoints GROUP BY thread_id
                                    HAVING max((checkpoint ->> 'ts')::timestamptz) < now() - make_interval(days => %s))
                                , w AS (DELETE FROM checkpoint_writes WHERE thread_id IN (SELECT thread_id FROM old))
                                , b AS (DELETE FROM checkpoint_blobs WHERE thread_id IN (SELECT thread_id FROM old))
                                DELETE FROM checkpoints WHERE thread_id IN (SELECT thread_id FROM old)""",
                                              (self.settings.checkpoint_retention_days,))
                            if cur.rowcount:
                                log.info("보존 기간이 지난 대화 체크포인트 %d건 삭제", cur.rowcount)
                        finally:
                            await cur.execute("SELECT pg_advisory_unlock(7420010)")
            except asyncio.CancelledError:
                return
            except Exception as e:  # noqa: BLE001 — 정리 실패가 서비스를 멈추지 않게
                log.warning("대화 기록 정리 실패: %s", e)


def create_app(service: AgentService | None = None, auth_config: AuthConfig | None | bool = True) -> FastAPI:
    """auth_config=True이면 환경 변수에서 읽는다(AUTH_MODE=disabled면 인증 없음)."""
    setup_logging("ai-agent")
    settings = service.settings if service else Settings()
    if auth_config is True:
        auth_config = AuthConfig.from_env()
    if service is None:
        service = AgentService(settings, authenticator=Authenticator(auth_config) if auth_config else None)
    if auth_config is None or auth_config is False:
        log.warning("AUTH_MODE=disabled: 인증 없이 동작합니다. 로컬 개발에서만 쓰세요.")
    limiter = RateLimiter(settings.rate_per_minute, settings.rate_burst)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # MCP 서버가 늦게 뜰 수 있으므로 시작 시 실패해도 첫 요청에서 다시 시도한다.
        try:
            await service.graph()
        except Exception as e:
            log.warning("시작 시 MCP 도구 로드 실패(첫 요청에서 재시도): %s", e)
        yield
        await service.aclose()

    app = FastAPI(title="TwinPilot Agent", lifespan=lifespan, docs_url=None, redoc_url=None)
    app.state.service = service
    setup_tracing("ai-agent", app)

    @app.exception_handler(AuthError)
    async def auth_error(request: Request, e: AuthError) -> JSONResponse:
        headers = {"WWW-Authenticate": 'Bearer realm="twinpilot"'} if e.status == 401 else {}
        return JSONResponse({"status": e.status, "detail": str(e)}, status_code=e.status, headers=headers,
                            media_type="application/problem+json")

    async def current_user(request: Request) -> User:
        if service.authenticator is None:
            return DEV_USER
        user = await service.authenticator.authenticate(request.headers.get("authorization"))
        if not user.has("VIEWER"):
            raise AuthError(403, "VIEWER 역할이 필요합니다.")
        return user

    @app.get("/agent/health")
    async def health() -> dict[str, Any]:
        return {"status": "UP", "mode": service.mode, "model": service.model_name, "toolsLoaded": service.ready}

    @app.get("/metrics", include_in_schema=False)
    async def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @app.post("/agent/chat")
    async def chat(req: ChatRequest, user: User = Depends(current_user)) -> StreamingResponse:
        if not req.message.strip():
            raise HTTPException(400, "message is blank")
        allowed, retry_after = limiter.allow(user.subject)
        if not allowed:
            RATE_LIMITED.inc()
            return JSONResponse({"status": 429, "detail": "요청이 너무 잦습니다. 잠시 후 다시 시도하세요."},
                                status_code=429, headers={"Retry-After": str(max(1, int(retry_after + 0.5)))},
                                media_type="application/problem+json")
        return StreamingResponse(service.chat(req, user), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @app.get("/agent/sessions/{session_id}/messages")
    async def history(session_id: str = Path(pattern=SESSION_PATTERN),
                      user: User = Depends(current_user)) -> dict[str, Any]:
        return {"sessionId": session_id, "messages": await service.history(user, session_id)}

    return app


def __getattr__(name: str):
    # uvicorn twinpilot_agent.app:app — 가져올 때 앱을 만든다(테스트에서 import만 해도 환경을 읽지 않도록).
    if name == "app":
        globals()["app"] = create_app()
        return globals()["app"]
    raise AttributeError(name)
