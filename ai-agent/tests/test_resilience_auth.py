"""LLM 장애 시 규칙 기반 전환, 사용자별 대화 격리, HTTP 인증·한도, MCP 토큰 교환."""
import json
import time

import anthropic
import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from langchain_core.messages import AIMessage

from twinpilot_agent.app import AgentService, ChatRequest, create_app
from twinpilot_agent.auth import AuthConfig, Authenticator, McpAuth, User, mcp_token
from twinpilot_agent.config import Settings

from .test_graph import coverage_tool, scripted

ISSUER = "https://idp.test/realms/twinpilot"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def overloaded() -> anthropic.APIStatusError:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    return anthropic.InternalServerError("overloaded", response=httpx.Response(529, request=request), body=None)


class FailingModel:
    """앞의 응답들을 차례로 내고, 그 뒤에는 지정한 예외를 던진다."""

    def __new__(cls, responses, error):
        model = scripted(*responses)
        original = model._generate

        def generate(messages, stop=None, run_manager=None, **kwargs):
            if not model.responses:
                raise error
            return original(messages, stop, run_manager, **kwargs)

        object.__setattr__(model, "_generate", generate)
        return model


async def events_of(service: AgentService, message: str, user: User, session: str = "s1"):
    out = []
    async for raw in service.chat(ChatRequest(message=message, sessionId=session), user):
        head, data = raw.strip().split("\n", 1)
        out.append((head.removeprefix("event: "), json.loads(data.removeprefix("data: "))))
    return out


USER = User(subject="u-1", username="analyst", roles=frozenset({"ANALYST"}))


async def test_llm_outage_falls_back_to_rule_planner_in_the_same_turn():
    calls = []
    service = AgentService(Settings(mode="llm", max_tool_rounds=3), tools=[coverage_tool(calls)],
                           llm=FailingModel([], overloaded()))
    events = await events_of(service, "다솜동 소화전 커버리지는?", USER)
    names = [e for e, _ in events]
    assert names[0] == "notice" and events[0][1]["kind"] == "fallback"
    assert "tool_call" in names and "error" not in names
    assert dict(events)["done"]["mode"] == "rule-fallback"
    assert "89.7%" in dict(events)["message"]["content"]
    assert calls == [{"facility_type": "HYDRANT", "region": "다솜동"}]


async def test_outage_after_tool_results_reuses_them_instead_of_calling_again():
    calls = []
    first = AIMessage(content="", tool_calls=[{"name": "analyze_coverage", "args": {"facility_type": "HYDRANT",
                                                                                   "region": "다솜동"},
                                               "id": "t1", "type": "tool_call"}])
    service = AgentService(Settings(mode="llm"), tools=[coverage_tool(calls)], llm=FailingModel([first], overloaded()))
    events = await events_of(service, "다솜동 소화전 커버리지는?", USER)
    assert len(calls) == 1                                   # 도구를 다시 부르지 않는다
    assert "89.7%" in dict(events)["message"]["content"]


async def test_non_outage_errors_are_reported_not_masked():
    service = AgentService(Settings(mode="llm"), tools=[coverage_tool([])],
                           llm=FailingModel([], ValueError("bug")))
    events = await events_of(service, "안녕", USER)
    assert [e for e, _ in events] == ["error"]


async def test_conversations_are_isolated_per_user():
    model = scripted(AIMessage(content="A의 답"), AIMessage(content="B의 답"))
    service = AgentService(Settings(mode="llm"), tools=[coverage_tool([])], llm=model)
    other = User(subject="u-2", username="viewer", roles=frozenset({"VIEWER"}))
    await events_of(service, "A 질문", USER, session="same")
    await events_of(service, "B 질문", other, session="same")
    # 같은 세션 ID라도 다른 사용자의 기록은 보이지 않는다.
    assert [m.type for m in model.seen[1]] == ["system", "human"]
    history = await service.history(USER, "same")
    assert history == [{"role": "user", "content": "A 질문"}, {"role": "assistant", "content": "A의 답"}]


# ----------------------------------------------------------------- HTTP + 인증


class FakeJwks:
    def get_signing_key_from_jwt(self, token):
        return type("K", (), {"key": KEY.public_key()})()


def token(roles=("VIEWER",), aud="twinpilot-agent", sub="user-9"):
    now = int(time.time())
    return jwt.encode({"iss": ISSUER, "aud": aud, "sub": sub, "iat": now, "exp": now + 300,
                       "preferred_username": "kim", "realm_access": {"roles": list(roles)}}, KEY, algorithm="RS256")


class FakeIdp:
    def __init__(self):
        self.calls = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        form = dict(httpx.QueryParams(request.content.decode()))
        self.calls.append(form)
        return httpx.Response(200, json={"access_token": f"mcp-{len(self.calls)}", "expires_in": 300})


def authenticator(idp=None):
    config = AuthConfig(issuer=ISSUER, jwks_url="unused", token_url="https://idp.test/token", client_secret="s")
    http = httpx.AsyncClient(transport=httpx.MockTransport(idp or FakeIdp()))
    return Authenticator(config, jwk_client=FakeJwks(), http=http)


@pytest.fixture
def client():
    settings = Settings(mode="rule", rate_per_minute=60, rate_burst=2)
    service = AgentService(settings, tools=[coverage_tool([])], authenticator=authenticator())
    app = create_app(service, auth_config=service.authenticator.config)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://agent")


async def test_chat_requires_valid_token_and_viewer_role(client):
    async with client:
        body = {"message": "다솜동 소화전 커버리지는?", "sessionId": "abc"}
        r = await client.post("/agent/chat", json=body)
        assert r.status_code == 401 and r.headers["www-authenticate"].startswith("Bearer")
        r = await client.post("/agent/chat", json=body, headers={"Authorization": f"Bearer {token(aud='twinpilot-api')}"})
        assert r.status_code == 401
        r = await client.post("/agent/chat", json=body, headers={"Authorization": f"Bearer {token(roles=('INGEST',))}"})
        assert r.status_code == 403
        r = await client.post("/agent/chat", json=body, headers={"Authorization": f"Bearer {token()}"})
        assert r.status_code == 200 and "event: done" in r.text
        bad = await client.post("/agent/chat", json={"message": "x", "sessionId": "../etc"},
                                headers={"Authorization": f"Bearer {token()}"})
        assert bad.status_code == 422
        history = await client.get("/agent/sessions/abc/messages", headers={"Authorization": f"Bearer {token()}"})
        assert history.json()["messages"][0] == {"role": "user", "content": "다솜동 소화전 커버리지는?"}
        assert "twinpilot_agent_chat_total" in (await client.get("/metrics")).text


async def test_per_user_rate_limit(client):
    async with client:
        headers = {"Authorization": f"Bearer {token(sub='busy')}"}
        statuses = [(await client.post("/agent/chat", json={"message": "안녕"}, headers=headers)).status_code
                    for _ in range(3)]
        assert statuses[:2] == [200, 200] and statuses[2] == 429
        other = await client.post("/agent/chat", json={"message": "안녕"},
                                  headers={"Authorization": f"Bearer {token(sub='calm')}"})
        assert other.status_code == 200


async def test_mcp_token_exchange_is_cached_and_attached_per_request():
    idp = FakeIdp()
    auth = authenticator(idp)
    user = User(subject="u", username="kim", roles=frozenset({"VIEWER"}), token=token())
    first = await auth.mcp_token_for(user)
    assert await auth.mcp_token_for(user) == first and len(idp.calls) == 1
    assert idp.calls[0]["audience"] == "twinpilot-mcp" and idp.calls[0]["subject_token"] == user.token

    seen = []
    transport = httpx.MockTransport(lambda r: seen.append(r.headers["authorization"]) or httpx.Response(200))
    async with httpx.AsyncClient(transport=transport, auth=McpAuth(auth)) as c:
        await c.get("http://mcp/mcp")                         # 사용자 없음 → 서비스 계정
        mcp_token.set("user-mcp-token")
        await c.get("http://mcp/mcp")
    assert seen == ["Bearer mcp-2", "Bearer user-mcp-token"]
    assert idp.calls[1]["grant_type"] == "client_credentials"
