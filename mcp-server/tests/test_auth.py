"""MCP 서버 인증: JWT 검증, 토큰 교환(전달 금지), 보호 리소스 메타데이터."""
import time

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from mcp.server.auth.middleware.auth_context import auth_context_var
from mcp.server.auth.middleware.bearer_auth import AuthenticatedUser
from mcp.shared.memory import create_connected_server_and_client_session

from twinpilot_mcp.auth import AuthConfig, JwtVerifier, UpstreamTokens
from twinpilot_mcp.backend import TwinApi
from twinpilot_mcp.server import create_server

from .test_server import FakeBackend

ISSUER = "https://idp.test/realms/twinpilot"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER = rsa.generate_private_key(public_exponent=65537, key_size=2048)


class FakeJwks:
    def get_signing_key_from_jwt(self, token):
        return type("K", (), {"key": KEY.public_key()})()


def token(aud="twinpilot-mcp", exp_offset=300, key=KEY, sub="user-1", iss=ISSUER):
    now = int(time.time())
    return jwt.encode({"iss": iss, "aud": aud, "sub": sub, "iat": now, "exp": now + exp_offset, "azp": "twinpilot-agent",
                       "preferred_username": "analyst", "realm_access": {"roles": ["ANALYST", "VIEWER"]}},
                      key, algorithm="RS256", headers={"kid": "k1"})


def verifier():
    return JwtVerifier(ISSUER, "unused", "twinpilot-mcp", jwk_client=FakeJwks())


async def test_verifier_accepts_only_tokens_issued_for_this_server():
    v = verifier()
    ok = await v.verify_token(token())
    assert ok is not None and ok.subject == "user-1" and ok.scopes == ["ANALYST", "VIEWER"]
    assert ok.claims["preferred_username"] == "analyst"
    assert await v.verify_token(token(aud="twinpilot-api")) is None        # 다른 서비스용 토큰
    assert await v.verify_token(token(exp_offset=-120)) is None            # 만료
    assert await v.verify_token(token(key=OTHER)) is None                  # 서명 위조
    assert await v.verify_token(token(iss="https://evil.test")) is None    # 다른 발급자
    assert await v.verify_token("garbage") is None


class FakeIdp:
    """토큰 교환·클라이언트 자격 증명 요청을 기록하고 새 토큰을 돌려준다."""

    def __init__(self):
        self.requests: list[dict] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        form = dict(httpx.QueryParams(request.content.decode()))
        self.requests.append(form)
        issued = "api-token-for-" + ("service" if form["grant_type"] == "client_credentials" else form["subject_token"][-8:])
        return httpx.Response(200, json={"access_token": issued, "expires_in": 300})


def config():
    return AuthConfig(issuer=ISSUER, jwks_url="unused", token_url="https://idp.test/token", client_secret="s3cret")


async def test_user_token_is_exchanged_not_forwarded_and_cached():
    idp = FakeIdp()
    backend = FakeBackend()
    upstream = UpstreamTokens(config(), http=httpx.AsyncClient(transport=httpx.MockTransport(idp)))
    server = create_server(TwinApi("http://twin-api", transport=httpx.MockTransport(backend)), auth=config(),
                           verifier=verifier(), upstream=upstream)
    incoming = token()
    access = await verifier().verify_token(incoming)
    reset = auth_context_var.set(AuthenticatedUser(access))
    try:
        async with create_connected_server_and_client_session(server) as client:
            res = await client.call_tool("analyze_coverage", {"facility_type": "HYDRANT", "region": "다솜동"})
            assert not res.isError
            await client.call_tool("list_regions", {})
    finally:
        auth_context_var.reset(reset)
    sent = {r.headers.get("authorization") for r in backend.requests}
    assert sent == {"Bearer api-token-for-" + incoming[-8:]}
    assert all(incoming not in (r.headers.get("authorization") or "") for r in backend.requests)
    exchanges = [r for r in idp.requests if r["grant_type"].endswith("token-exchange")]
    assert len(exchanges) == 1                                   # 캐시: 같은 사용자 토큰은 한 번만 교환
    assert exchanges[0]["audience"] == "twinpilot-api" and exchanges[0]["subject_token"] == incoming


async def test_without_user_context_falls_back_to_service_account():
    idp = FakeIdp()
    backend = FakeBackend()
    upstream = UpstreamTokens(config(), http=httpx.AsyncClient(transport=httpx.MockTransport(idp)))
    server = create_server(TwinApi("http://twin-api", transport=httpx.MockTransport(backend)), auth=config(),
                           verifier=verifier(), upstream=upstream)
    async with create_connected_server_and_client_session(server) as client:
        assert not (await client.call_tool("list_regions", {})).isError
    assert backend.requests[0].headers["authorization"] == "Bearer api-token-for-service"
    assert idp.requests[0]["grant_type"] == "client_credentials"


async def test_http_endpoint_requires_bearer_and_publishes_resource_metadata():
    server = create_server(TwinApi("http://twin-api", transport=httpx.MockTransport(FakeBackend())),
                           auth=config(), verifier=verifier())
    app = server.streamable_http_app()
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://localhost:8090") as client:
        unauthorized = await client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                                         headers={"Accept": "application/json, text/event-stream"})
        assert unauthorized.status_code == 401
        assert "resource_metadata" in unauthorized.headers["www-authenticate"]
        wrong_aud = await client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                                      headers={"Authorization": f"Bearer {token(aud='twinpilot-api')}",
                                               "Accept": "application/json, text/event-stream"})
        assert wrong_aud.status_code == 401
        meta = await client.get("/.well-known/oauth-protected-resource/mcp")
        assert meta.status_code == 200 and meta.json()["authorization_servers"] == [ISSUER]
        assert (await client.get("/healthz")).text == "ok"
        assert "twinpilot_mcp_tool_calls_total" in (await client.get("/metrics")).text


def test_auth_mode_disabled_returns_none(monkeypatch):
    monkeypatch.setenv("MCP_AUTH_MODE", "disabled")
    assert AuthConfig.from_env() is None
    monkeypatch.setenv("MCP_AUTH_MODE", "oidc")
    monkeypatch.setenv("OIDC_ISSUER", "http://kc/realms/x/")
    monkeypatch.setenv("OIDC_INTERNAL_URL", "http://keycloak:8080/realms/x")
    c = AuthConfig.from_env()
    assert c.issuer == "http://kc/realms/x" and c.token_url == "http://keycloak:8080/realms/x/protocol/openid-connect/token"


@pytest.fixture(autouse=True)
def _no_proxy(monkeypatch):
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
        monkeypatch.delenv(k, raising=False)
