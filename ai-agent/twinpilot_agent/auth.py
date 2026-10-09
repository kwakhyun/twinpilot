"""사용자 인증과 MCP 서버용 토큰.

- 브라우저가 보낸 액세스 토큰(aud=twinpilot-agent)을 JWKS로 검증하고 사용자·역할을 얻는다.
- MCP 서버에는 받은 토큰을 그대로 넘기지 않고, 표준 토큰 교환(RFC 8693)으로 같은 사용자의
  MCP 서버용 토큰(aud=twinpilot-mcp)을 받아 도구 호출마다 붙인다.
- 도구 목록 조회처럼 사용자가 없는 호출은 에이전트 서비스 계정(client credentials) 토큰을 쓴다.
"""
from __future__ import annotations

import asyncio
import contextvars
import hashlib
import logging
import os
import time
from dataclasses import dataclass, field
from typing import Any

import httpx
import jwt

log = logging.getLogger(__name__)

TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange"
ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"

# 현재 요청 사용자의 MCP용 토큰. 그래프 실행(도구 호출)은 같은 컨텍스트를 이어받는다.
mcp_token: contextvars.ContextVar[str | None] = contextvars.ContextVar("mcp_token", default=None)


@dataclass(frozen=True)
class User:
    subject: str
    username: str
    roles: frozenset[str] = field(default_factory=frozenset)
    token: str | None = None

    def has(self, role: str) -> bool:
        hierarchy = {"ADMIN": 4, "PLANNER": 3, "ANALYST": 2, "VIEWER": 1}
        mine = max((hierarchy.get(r, 0) for r in self.roles), default=0)
        return role in self.roles or mine >= hierarchy.get(role, 99)


DEV_USER = User(subject="dev", username="dev", roles=frozenset({"ADMIN"}))


@dataclass(frozen=True)
class AuthConfig:
    issuer: str
    jwks_url: str
    token_url: str
    audience: str = "twinpilot-agent"
    client_id: str = "twinpilot-agent"
    client_secret: str = ""
    mcp_audience: str = "twinpilot-mcp"

    @staticmethod
    def from_env() -> AuthConfig | None:
        if os.getenv("AUTH_MODE", "oidc").lower() == "disabled":
            return None
        issuer = os.getenv("OIDC_ISSUER", "http://localhost:8088/auth/realms/twinpilot").rstrip("/")
        internal = os.getenv("OIDC_INTERNAL_URL", issuer).rstrip("/")
        return AuthConfig(
            issuer=issuer,
            jwks_url=os.getenv("OIDC_JWKS_URL", f"{internal}/protocol/openid-connect/certs"),
            token_url=os.getenv("OIDC_TOKEN_URL", f"{internal}/protocol/openid-connect/token"),
            audience=os.getenv("AGENT_AUDIENCE", "twinpilot-agent"),
            client_id=os.getenv("AGENT_CLIENT_ID", "twinpilot-agent"),
            client_secret=os.getenv("AGENT_CLIENT_SECRET", ""),
            mcp_audience=os.getenv("MCP_AUDIENCE", "twinpilot-mcp"),
        )


class AuthError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class Authenticator:
    def __init__(self, config: AuthConfig, *, jwk_client: Any = None, http: httpx.AsyncClient | None = None):
        self.config = config
        self._jwks = jwk_client or jwt.PyJWKClient(config.jwks_url, cache_keys=True, lifespan=300, timeout=5)
        self._http = http or httpx.AsyncClient(timeout=10)
        self._cache: dict[str, tuple[str, float]] = {}
        self._service_lock = asyncio.Lock()

    async def aclose(self) -> None:
        await self._http.aclose()

    def _decode(self, token: str) -> dict[str, Any]:
        key = self._jwks.get_signing_key_from_jwt(token)
        return jwt.decode(token, key.key, algorithms=["RS256", "ES256"], audience=self.config.audience,
                          issuer=self.config.issuer, options={"require": ["exp", "iat", "sub"]}, leeway=30)

    async def authenticate(self, authorization: str | None) -> User:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise AuthError(401, "인증이 필요합니다.")
        token = authorization[7:].strip()
        try:
            claims = await asyncio.to_thread(self._decode, token)
        except (jwt.PyJWTError, jwt.PyJWKClientError) as e:
            log.info("토큰 거부: %s", e)
            raise AuthError(401, "유효하지 않은 토큰입니다.") from e
        roles = frozenset((claims.get("realm_access") or {}).get("roles") or [])
        return User(subject=claims["sub"], username=claims.get("preferred_username") or claims["sub"], roles=roles,
                    token=token)

    async def mcp_token_for(self, user: User) -> str | None:
        """사용자 토큰을 MCP 서버용으로 교환한다(만료 30초 전까지 캐시)."""
        if user.token is None:
            return None
        key = hashlib.sha256(user.token.encode()).hexdigest()
        cached = self._cache.get(key)
        if cached and cached[1] > time.time():
            return cached[0]
        token, ttl = await self._request({
            "grant_type": TOKEN_EXCHANGE, "subject_token": user.token, "subject_token_type": ACCESS_TOKEN_TYPE,
            "requested_token_type": ACCESS_TOKEN_TYPE, "audience": self.config.mcp_audience})
        self._remember(key, token, ttl)
        return token

    async def service_token(self) -> str:
        async with self._service_lock:
            cached = self._cache.get("__service__")
            if cached and cached[1] > time.time():
                return cached[0]
            token, ttl = await self._request({"grant_type": "client_credentials"})
            self._remember("__service__", token, ttl)
            return token

    def _remember(self, key: str, token: str, ttl: float) -> None:
        if len(self._cache) > 5000:
            now = time.time()
            self._cache = {k: v for k, v in self._cache.items() if v[1] > now}
        self._cache[key] = (token, time.time() + max(0.0, ttl - 30))

    async def _request(self, form: dict[str, str]) -> tuple[str, float]:
        res = await self._http.post(self.config.token_url, data=form,
                                    auth=(self.config.client_id, self.config.client_secret))
        if res.status_code != 200:
            raise AuthError(502, f"토큰 발급 실패({res.status_code})")
        body = res.json()
        return body["access_token"], float(body.get("expires_in", 60))


class McpAuth(httpx.Auth):
    """MCP 요청에 토큰을 붙인다. 요청 사용자 토큰(컨텍스트)이 있으면 그것을, 없으면 서비스 계정 토큰을 쓴다."""

    def __init__(self, authenticator: Authenticator):
        self.authenticator = authenticator

    async def async_auth_flow(self, request: httpx.Request):
        token = mcp_token.get() or await self.authenticator.service_token()
        request.headers["Authorization"] = f"Bearer {token}"
        yield request
