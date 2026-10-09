"""OIDC 인증.

- 들어오는 요청: Keycloak이 이 서버(aud=twinpilot-mcp)용으로 발급한 JWT만 받는다(서명·만료·발급자·대상 검증).
- 나가는 요청(twin-api): 받은 토큰을 그대로 넘기지 않는다(MCP 권고: token passthrough 금지).
  표준 토큰 교환(RFC 8693)으로 같은 사용자의 twin-api용 토큰(aud=twinpilot-api)을 받아 쓴다.
  사용자 컨텍스트가 없는 stdio 모드에서는 서비스 계정(client credentials) 토큰을 쓴다.
"""
from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import time
from dataclasses import dataclass
from typing import Any

import httpx
import jwt
from mcp.server.auth.provider import AccessToken, TokenVerifier

log = logging.getLogger(__name__)

TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange"
ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"


@dataclass(frozen=True)
class AuthConfig:
    issuer: str
    jwks_url: str
    token_url: str
    audience: str = "twinpilot-mcp"
    client_id: str = "twinpilot-mcp"
    client_secret: str = ""
    upstream_audience: str = "twinpilot-api"
    resource_url: str = "http://localhost:8090/mcp"

    @staticmethod
    def from_env() -> AuthConfig | None:
        """MCP_AUTH_MODE=disabled이면 None(로컬 개발 전용)."""
        if os.getenv("MCP_AUTH_MODE", "oidc").lower() == "disabled":
            return None
        issuer = os.getenv("OIDC_ISSUER", "http://localhost:8088/auth/realms/twinpilot").rstrip("/")
        internal = os.getenv("OIDC_INTERNAL_URL", issuer).rstrip("/")
        return AuthConfig(
            issuer=issuer,
            jwks_url=os.getenv("OIDC_JWKS_URL", f"{internal}/protocol/openid-connect/certs"),
            token_url=os.getenv("OIDC_TOKEN_URL", f"{internal}/protocol/openid-connect/token"),
            audience=os.getenv("MCP_AUDIENCE", "twinpilot-mcp"),
            client_id=os.getenv("MCP_CLIENT_ID", "twinpilot-mcp"),
            client_secret=os.getenv("MCP_CLIENT_SECRET", ""),
            upstream_audience=os.getenv("TWIN_API_AUDIENCE", "twinpilot-api"),
            resource_url=os.getenv("MCP_RESOURCE_URL", "http://localhost:8090/mcp"),
        )


class JwtVerifier(TokenVerifier):
    """JWKS로 서명을 검증한다. 키는 캐시하고, 모르는 kid가 오면 다시 받는다(키 교체 대응)."""

    def __init__(self, issuer: str, jwks_url: str, audience: str, *, jwk_client: Any = None):
        self.issuer = issuer
        self.audience = audience
        self._jwks = jwk_client or jwt.PyJWKClient(jwks_url, cache_keys=True, lifespan=300, timeout=5)

    def decode(self, token: str) -> dict[str, Any]:
        key = self._jwks.get_signing_key_from_jwt(token)
        return jwt.decode(token, key.key, algorithms=["RS256", "ES256"], audience=self.audience, issuer=self.issuer,
                          options={"require": ["exp", "iat", "sub"]}, leeway=30)

    async def verify_token(self, token: str) -> AccessToken | None:
        try:
            claims = await asyncio.to_thread(self.decode, token)
        except (jwt.PyJWTError, jwt.PyJWKClientError) as e:
            log.info("토큰 거부: %s", e)
            return None
        roles = sorted((claims.get("realm_access") or {}).get("roles") or [])
        return AccessToken(token=token, client_id=claims.get("azp") or "unknown", scopes=roles,
                           expires_at=claims.get("exp"), resource=self.audience, subject=claims.get("sub"),
                           claims={"preferred_username": claims.get("preferred_username"), "iss": claims.get("iss")})


class UpstreamTokens:
    """twin-api 호출용 토큰. 교환 결과는 만료 30초 전까지 캐시한다(사용자 토큰 해시 기준)."""

    def __init__(self, config: AuthConfig, http: httpx.AsyncClient | None = None):
        self.config = config
        self._http = http or httpx.AsyncClient(timeout=10)
        self._cache: dict[str, tuple[str, float]] = {}
        self._lock = asyncio.Lock()

    async def aclose(self) -> None:
        await self._http.aclose()

    async def for_user(self, subject_token: str) -> str:
        key = hashlib.sha256(subject_token.encode()).hexdigest()
        cached = self._cache.get(key)
        if cached and cached[1] > time.time():
            return cached[0]
        token, expires_in = await self._token({
            "grant_type": TOKEN_EXCHANGE, "subject_token": subject_token, "subject_token_type": ACCESS_TOKEN_TYPE,
            "requested_token_type": ACCESS_TOKEN_TYPE, "audience": self.config.upstream_audience})
        self._remember(key, token, expires_in)
        return token

    async def service(self) -> str:
        async with self._lock:
            cached = self._cache.get("__service__")
            if cached and cached[1] > time.time():
                return cached[0]
            token, expires_in = await self._token({"grant_type": "client_credentials"})
            self._remember("__service__", token, expires_in)
            return token

    def invalidate(self, subject_token: str | None) -> None:
        self._cache.pop("__service__" if subject_token is None
                        else hashlib.sha256(subject_token.encode()).hexdigest(), None)

    def _remember(self, key: str, token: str, expires_in: float) -> None:
        if len(self._cache) > 5000:   # 오래된 항목 정리(사용자 수 대비 충분히 큼)
            now = time.time()
            self._cache = {k: v for k, v in self._cache.items() if v[1] > now}
        self._cache[key] = (token, time.time() + max(0.0, expires_in - 30))

    async def _token(self, form: dict[str, str]) -> tuple[str, float]:
        res = await self._http.post(self.config.token_url, data=form,
                                    auth=(self.config.client_id, self.config.client_secret))
        if res.status_code != 200:
            raise PermissionError(f"토큰 발급 실패({res.status_code}): {res.text[:200]}")
        body = res.json()
        return body["access_token"], float(body.get("expires_in", 60))
