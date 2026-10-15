"""데모용 경량 OIDC 발급자(메모리 1GB 서버용 Keycloak 대체).

Keycloak과 같은 경로·토큰 형식(realm_access.roles, preferred_username, azp)을 써서
twin-api·mcp-server·ai-agent·frontend는 설정값(주소)만 바꿔 그대로 쓴다.

지원 범위
- Authorization Code + PKCE(S256): 웹 클라이언트(twinpilot-web) 로그인, SSO 쿠키
- refresh token 회전(재사용 감지 시 세션 종료)
- client_credentials: 에이전트·MCP·센서 게이트웨이 서비스 계정
- token exchange(RFC 8693): 에이전트 → MCP, MCP → twin-api
- 로그아웃(end session), userinfo, JWKS, discovery

데모 전용이다. 사용자는 고정 4계정, 세션·코드는 메모리에만 둔다(재시작하면 다시 로그인).
운영 환경은 Keycloak(infra/keycloak)을 쓴다.
"""
from __future__ import annotations

import base64
import hashlib
import html
import json
import logging
import os
import secrets
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote, unquote, urlencode

import jwt
import uvicorn
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import HTMLResponse, JSONResponse, PlainTextResponse, RedirectResponse, Response
from starlette.routing import Route

log = logging.getLogger("demo-idp")

REALM_PATH = "/auth/realms/twinpilot"
OIDC = f"{REALM_PATH}/protocol/openid-connect"
TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange"
ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"
WEB_CLIENT = "twinpilot-web"


def _env_int(name: str, default: int) -> int:
    return int(os.getenv(name, str(default)))


PUBLIC_URL = os.getenv("PUBLIC_URL", "http://localhost:8088").rstrip("/")
ISSUER = PUBLIC_URL + REALM_PATH
ACCESS_TTL = _env_int("ACCESS_TOKEN_TTL", 300)
CODE_TTL = 60
SSO_IDLE = _env_int("SSO_IDLE_TIMEOUT", 1800)
SSO_MAX = _env_int("SSO_MAX_LIFESPAN", 36000)
LOGIN_HINT = os.getenv("LOGIN_HINT", "")


# ------------------------------------------------------------------ 사용자·클라이언트

@dataclass(frozen=True)
class Account:
    username: str
    password: str
    roles: tuple[str, ...]

    @property
    def subject(self) -> str:
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"twinpilot:{self.username}"))


def _accounts() -> dict[str, Account]:
    demo_pw = os.getenv("DEMO_USER_PASSWORD", "")
    admin_pw = os.getenv("ADMIN_PASSWORD", "")
    accounts = {}
    if demo_pw:
        for name, role in (("viewer", "VIEWER"), ("analyst", "ANALYST"), ("planner", "PLANNER")):
            accounts[name] = Account(name, demo_pw, (role,))
    if admin_pw:   # 비어 있으면 admin 계정을 열지 않는다(데이터 적재·감사 로그는 공개 데모에서 막는다)
        accounts["admin"] = Account("admin", admin_pw, ("ADMIN",))
    return accounts


@dataclass(frozen=True)
class Client:
    client_id: str
    secret: str
    service_roles: tuple[str, ...] = ()          # client_credentials 토큰의 역할
    service_audience: str | None = None          # client_credentials 토큰의 aud
    exchange_audiences: tuple[str, ...] = ()     # token exchange로 요청할 수 있는 aud


def _clients() -> dict[str, Client]:
    clients = [
        Client("twinpilot-agent", os.getenv("AGENT_CLIENT_SECRET", ""), (), "twinpilot-mcp", ("twinpilot-mcp",)),
        Client("twinpilot-mcp", os.getenv("MCP_CLIENT_SECRET", ""), ("ANALYST",), "twinpilot-api", ("twinpilot-api",)),
        Client("twinpilot-sensor-gateway", os.getenv("SENSOR_GATEWAY_SECRET", ""), ("INGEST",), "twinpilot-api"),
    ]
    return {c.client_id: c for c in clients if c.secret}


ACCOUNTS = _accounts()
CLIENTS = _clients()


# ------------------------------------------------------------------ 서명 키

def _load_key() -> rsa.RSAPrivateKey:
    """키를 볼륨에 보관해 재시작해도 이미 발급한 토큰과 각 서비스의 JWKS 캐시가 유효하게 한다."""
    path = Path(os.getenv("KEY_FILE", "/data/signing-key.pem"))
    if path.exists():
        return serialization.load_pem_private_key(path.read_bytes(), password=None)  # type: ignore[return-value]
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption()))
    path.chmod(0o600)
    log.info("새 서명 키를 만들었습니다: %s", path)
    return key


KEY = _load_key()
PUBLIC_KEY = KEY.public_key()
KID = hashlib.sha256(PUBLIC_KEY.public_bytes(serialization.Encoding.DER,
                                             serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()[:16]
JWKS = {"keys": [{**json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(PUBLIC_KEY)), "kid": KID, "use": "sig",
                  "alg": "RS256"}]}


def sign(claims: dict) -> str:
    return jwt.encode(claims, KEY, algorithm="RS256", headers={"kid": KID, "typ": "JWT"})


def verify(token: str, audience: str | None = None) -> dict:
    options = {"require": ["exp", "iat", "iss", "sub"], "verify_aud": audience is not None}
    return jwt.decode(token, PUBLIC_KEY, algorithms=["RS256"], issuer=ISSUER, audience=audience, options=options,
                      leeway=5)


# ------------------------------------------------------------------ 세션 상태(메모리)

@dataclass
class Session:
    sid: str
    account: Account
    created: float
    last_seen: float
    chains: set[str] = field(default_factory=set)

    def alive(self, now: float) -> bool:
        return now - self.last_seen < SSO_IDLE and now - self.created < SSO_MAX


@dataclass
class PendingAuth:
    params: dict
    created: float


@dataclass
class AuthCode:
    sid: str
    redirect_uri: str
    challenge: str
    nonce: str | None
    created: float


@dataclass
class Refresh:
    sid: str
    chain: str
    nonce: str | None
    used: bool = False


_lock = threading.Lock()
SESSIONS: dict[str, Session] = {}
PENDING: dict[str, PendingAuth] = {}
CODES: dict[str, AuthCode] = {}
REFRESH: dict[str, Refresh] = {}
FAILURES: dict[str, list[float]] = {}     # 로그인 실패 시각(계정별·IP별)


def _cleanup(now: float) -> None:
    for sid in [s for s, v in SESSIONS.items() if not v.alive(now)]:
        _end_session(sid)
    for k in [k for k, v in PENDING.items() if now - v.created > 900]:
        PENDING.pop(k, None)
    for k in [k for k, v in CODES.items() if now - v.created > CODE_TTL]:
        CODES.pop(k, None)
    for k in [k for k, v in FAILURES.items() if not [t for t in v if now - t < 600]]:
        FAILURES.pop(k, None)


def _end_session(sid: str) -> None:
    SESSIONS.pop(sid, None)
    for k in [k for k, v in REFRESH.items() if v.sid == sid]:
        REFRESH.pop(k, None)


def _locked_out(keys: list[str], now: float) -> bool:
    # 계정별 5회, IP별 20회 실패(10분 안)면 잠시 막는다(무차별 대입 방어).
    limits = {"user": 5, "ip": 20}
    return any(len([t for t in FAILURES.get(k, []) if now - t < 600]) >= limits[k.split(":", 1)[0]] for k in keys)


# ------------------------------------------------------------------ 토큰 발급

def _base_claims(account: Account, sid: str | None, azp: str, aud: list[str] | str, now: int) -> dict:
    claims = {
        "iss": ISSUER, "sub": account.subject, "aud": aud, "azp": azp, "typ": "Bearer",
        "iat": now, "exp": now + ACCESS_TTL, "jti": secrets.token_hex(12),
        "preferred_username": account.username, "realm_access": {"roles": list(account.roles)},
        "scope": "openid profile",
    }
    if sid:
        claims["sid"] = sid
    return claims


def _user_tokens(session: Session, chain: str, nonce: str | None) -> dict:
    now = int(time.time())
    access = sign(_base_claims(session.account, session.sid, WEB_CLIENT, ["twinpilot-api", "twinpilot-agent"], now))
    id_claims = {"iss": ISSUER, "sub": session.account.subject, "aud": WEB_CLIENT, "azp": WEB_CLIENT,
                 "iat": now, "exp": now + ACCESS_TTL, "auth_time": int(session.created), "sid": session.sid,
                 "preferred_username": session.account.username, "typ": "ID"}
    if nonce:
        id_claims["nonce"] = nonce
    refresh = secrets.token_urlsafe(32)
    REFRESH[refresh] = Refresh(session.sid, chain, nonce)
    session.chains.add(chain)
    remaining = int(min(SSO_IDLE, SSO_MAX - (now - session.created)))
    return {"access_token": access, "token_type": "Bearer", "expires_in": ACCESS_TTL, "refresh_token": refresh,
            "refresh_expires_in": remaining, "id_token": sign(id_claims), "scope": "openid profile",
            "session_state": session.sid}


def _oauth_error(error: str, description: str, status: int = 400) -> JSONResponse:
    return JSONResponse({"error": error, "error_description": description}, status_code=status,
                        headers={"Cache-Control": "no-store"})


def _client_auth(request: Request, form) -> tuple[str | None, Client | None]:
    """Basic 또는 폼의 client_id·client_secret. 공개 클라이언트(웹)는 시크릿 없이 client_id만."""
    client_id, secret = form.get("client_id"), form.get("client_secret")
    header = request.headers.get("authorization", "")
    if header.lower().startswith("basic "):
        try:
            client_id, _, secret = base64.b64decode(header[6:]).decode().partition(":")
        except ValueError:
            return None, None
        client_id, secret = unquote(client_id), unquote(secret)
    if client_id == WEB_CLIENT and not secret:
        return WEB_CLIENT, None
    client = CLIENTS.get(client_id or "")
    if client and secret and secrets.compare_digest(client.secret, secret):
        return client.client_id, client
    return None, None


# ------------------------------------------------------------------ 엔드포인트

def _redirect_allowed(uri: str | None) -> bool:
    return bool(uri) and (uri == PUBLIC_URL or uri.startswith(PUBLIC_URL + "/"))


def _client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


async def discovery(request: Request) -> Response:
    return JSONResponse({
        "issuer": ISSUER,
        "authorization_endpoint": f"{PUBLIC_URL}{OIDC}/auth",
        "token_endpoint": f"{PUBLIC_URL}{OIDC}/token",
        "userinfo_endpoint": f"{PUBLIC_URL}{OIDC}/userinfo",
        "end_session_endpoint": f"{PUBLIC_URL}{OIDC}/logout",
        "jwks_uri": f"{PUBLIC_URL}{OIDC}/certs",
        "response_types_supported": ["code"],
        "response_modes_supported": ["query"],
        "grant_types_supported": ["authorization_code", "refresh_token", "client_credentials", TOKEN_EXCHANGE],
        "subject_types_supported": ["public"],
        "id_token_signing_alg_values_supported": ["RS256"],
        "code_challenge_methods_supported": ["S256"],
        "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post", "none"],
        "scopes_supported": ["openid", "profile"],
        "claims_supported": ["sub", "iss", "preferred_username", "realm_access"],
    })


async def certs(request: Request) -> Response:
    return JSONResponse(JWKS, headers={"Cache-Control": "max-age=300"})


# TwinPilot 마크(frontend/public/favicon.svg와 같은 도형)
LOGO_PATHS = ('<path d="M49.4 11.2L11.2 26.5L29 31.6Z" fill="#3987e5" stroke="#3987e5" stroke-width="2.6" '
              'stroke-linejoin="round"/><path d="M52.8 14.6L37.5 52.8L32.4 35Z" fill="none" stroke="#ff9a52" '
              'stroke-width="3.2" stroke-linejoin="round"/>')
LOGO_SVG = f'<svg class="logo" width="28" height="28" viewBox="9.2 9.2 45.6 45.6" aria-hidden="true">{LOGO_PATHS}</svg>'
FAVICON = "data:image/svg+xml," + quote(
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="15" '
    f'fill="#121820"/>{LOGO_PATHS}</svg>')

LOGIN_CSP = ("default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; form-action 'self'; "
             "frame-ancestors 'none'; base-uri 'none'")


def _page(title: str, body: str, status: int = 200) -> HTMLResponse:
    doc = f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{html.escape(title)} · TwinPilot</title>
<link rel="icon" href="{FAVICON}">
<style>
:root{{color-scheme:light dark;--bg:#0f1720;--card:#16212c;--fg:#e6edf3;--muted:#9fb0c0;--line:#2a3a4a;--accent:#2f7de1;--err:#ff8a80}}
@media (prefers-color-scheme: light){{:root{{--bg:#eef2f6;--card:#fff;--fg:#14202b;--muted:#4c5b69;--line:#cfd8e1;--accent:#1f63c1;--err:#b3261e}}}}
*{{box-sizing:border-box}}body{{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--fg);
font:15px/1.5 system-ui,-apple-system,"Apple SD Gothic Neo","Noto Sans KR",sans-serif;padding:16px}}
main{{width:100%;max-width:360px;background:var(--card);border:1px solid var(--line);border-radius:12px;padding:28px}}
h1{{font-size:20px;margin:0 0 4px;display:flex;align-items:center;gap:10px}}p{{margin:0 0 16px;color:var(--muted)}}label{{display:block;margin:12px 0 4px;font-weight:600}}
input{{width:100%;padding:10px 12px;border-radius:8px;border:1px solid var(--line);background:transparent;color:inherit;font:inherit}}
button{{margin-top:20px;width:100%;padding:11px;border:0;border-radius:8px;background:var(--accent);color:#fff;font:inherit;font-weight:600;cursor:pointer}}
.err{{color:var(--err);margin:12px 0 0}}.hint{{margin-top:16px;font-size:13px}}
</style></head><body><main>{body}</main></body></html>"""
    return HTMLResponse(doc, status_code=status, headers={
        "Content-Security-Policy": LOGIN_CSP, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
        "Referrer-Policy": "no-referrer"})


def _login_form(auth_id: str, error: str = "", username: str = "", status: int = 200) -> HTMLResponse:
    err = f'<p class="err" role="alert">{html.escape(error)}</p>' if error else ""
    hint = f'<p class="hint">{html.escape(LOGIN_HINT)}</p>' if LOGIN_HINT else ""
    body = f"""<h1>{LOGO_SVG}TwinPilot 로그인</h1><p>디지털트윈 안전 코파일럿 데모</p>
<form id="kc-form-login" method="post" action="{PUBLIC_URL}{OIDC}/auth?auth_id={html.escape(auth_id)}">
<input type="hidden" name="auth_id" value="{html.escape(auth_id)}">
<label for="username">계정</label><input id="username" name="username" autocomplete="username" required value="{html.escape(username)}" autofocus>
<label for="password">비밀번호</label><input id="password" name="password" type="password" autocomplete="current-password" required>
{err}<button id="kc-login" type="submit">로그인</button></form>{hint}"""
    return _page("로그인", body, status)


def _issue_code(params: dict, session: Session) -> RedirectResponse:
    code = secrets.token_urlsafe(32)
    CODES[code] = AuthCode(session.sid, params["redirect_uri"], params["code_challenge"], params.get("nonce"),
                           time.time())
    query = {"code": code, "iss": ISSUER, "session_state": session.sid}
    if params.get("state"):
        query["state"] = params["state"]
    sep = "&" if "?" in params["redirect_uri"] else "?"
    return RedirectResponse(params["redirect_uri"] + sep + urlencode(query), status_code=302)


def _sso_cookie(request: Request, response: Response, sid: str | None) -> None:
    secure = request.headers.get("x-forwarded-proto", request.url.scheme) == "https"
    if sid is None:
        response.delete_cookie("TP_SSO", path=REALM_PATH)
    else:
        response.set_cookie("TP_SSO", sid, max_age=SSO_MAX, path=REALM_PATH, httponly=True, samesite="lax",
                            secure=secure)


async def authorize(request: Request) -> Response:
    now = time.time()
    if request.method == "GET":
        params = dict(request.query_params)
        if params.get("client_id") != WEB_CLIENT or not _redirect_allowed(params.get("redirect_uri")):
            return _page("요청 오류", "<h1>잘못된 로그인 요청</h1><p>허용되지 않은 클라이언트나 돌아갈 주소입니다.</p>", 400)

        def back(error: str, desc: str) -> RedirectResponse:
            q = {"error": error, "error_description": desc, **({"state": params["state"]} if params.get("state") else {})}
            return RedirectResponse(params["redirect_uri"] + "?" + urlencode(q), status_code=302)

        if params.get("response_type") != "code":
            return back("unsupported_response_type", "code만 지원합니다")
        if not params.get("code_challenge") or params.get("code_challenge_method") != "S256":
            return back("invalid_request", "PKCE(S256)가 필요합니다")
        with _lock:
            _cleanup(now)
            session = SESSIONS.get(request.cookies.get("TP_SSO", ""))
            if session and session.alive(now):
                session.last_seen = now
                return _issue_code(params, session)
            if params.get("prompt") == "none":
                return back("login_required", "로그인이 필요합니다")
            auth_id = secrets.token_urlsafe(24)
            PENDING[auth_id] = PendingAuth(params, now)
        return _login_form(auth_id)

    form = await request.form()
    # 로그인 요청 식별값은 폼 action 주소(Keycloak과 같은 방식)나 숨은 필드로 받는다.
    auth_id = str(form.get("auth_id") or request.query_params.get("auth_id", ""))
    username = str(form.get("username", "")).strip().lower()
    password = str(form.get("password", ""))
    ip_key, user_key = f"ip:{_client_ip(request)}", f"user:{username}"
    with _lock:
        _cleanup(now)
        pending = PENDING.get(auth_id)
        if pending is None:
            return _page("요청 만료", '<h1>로그인 요청이 만료됐습니다</h1><p><a href="/">처음 화면</a>에서 다시 시도하세요.</p>', 400)
        if _locked_out([ip_key, user_key], now):
            return _login_form(auth_id, "로그인 실패가 많아 잠시 막혔습니다. 몇 분 뒤 다시 시도하세요.", username, 429)
        account = ACCOUNTS.get(username)
        if account is None or not secrets.compare_digest(account.password.encode(), password.encode()):
            FAILURES.setdefault(ip_key, []).append(now)
            FAILURES.setdefault(user_key, []).append(now)
            log.info("로그인 실패 user=%s ip=%s", username, _client_ip(request))
            return _login_form(auth_id, "계정이나 비밀번호가 맞지 않습니다.", username, 401)
        PENDING.pop(auth_id, None)
        FAILURES.pop(user_key, None)
        session = Session(secrets.token_urlsafe(24), account, now, now)
        SESSIONS[session.sid] = session
        log.info("로그인 user=%s", username)
        response = _issue_code(pending.params, session)
    _sso_cookie(request, response, session.sid)
    return response


async def token(request: Request) -> Response:
    form = await request.form()
    grant = form.get("grant_type")
    client_id, client = _client_auth(request, form)
    if client_id is None:
        return _oauth_error("invalid_client", "클라이언트 인증 실패", 401)
    now = time.time()

    if grant == "authorization_code":
        if client_id != WEB_CLIENT:
            return _oauth_error("unauthorized_client", "이 클라이언트는 로그인 흐름을 쓸 수 없습니다")
        with _lock:
            code = CODES.pop(str(form.get("code", "")), None)
            if code is None or now - code.created > CODE_TTL:
                return _oauth_error("invalid_grant", "코드가 없거나 만료됐습니다")
            if form.get("redirect_uri") != code.redirect_uri:
                return _oauth_error("invalid_grant", "redirect_uri가 다릅니다")
            verifier = str(form.get("code_verifier", ""))
            digest = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
            if not verifier or not secrets.compare_digest(digest, code.challenge):
                return _oauth_error("invalid_grant", "PKCE 검증 실패")
            session = SESSIONS.get(code.sid)
            if session is None or not session.alive(now):
                return _oauth_error("invalid_grant", "세션이 끝났습니다")
            session.last_seen = now
            body = _user_tokens(session, secrets.token_hex(8), code.nonce)
        return JSONResponse(body, headers={"Cache-Control": "no-store"})

    if grant == "refresh_token":
        if client_id != WEB_CLIENT:
            return _oauth_error("unauthorized_client", "refresh token은 웹 클라이언트만 씁니다")
        with _lock:
            _cleanup(now)
            entry = REFRESH.get(str(form.get("refresh_token", "")))
            if entry is None:
                return _oauth_error("invalid_grant", "refresh token이 유효하지 않습니다")
            if entry.used:
                # 이미 쓴 refresh token을 다시 썼다 → 탈취 가능성으로 보고 세션을 끝낸다(Keycloak과 같은 동작).
                log.warning("refresh token 재사용 감지 sid=%s", entry.sid)
                _end_session(entry.sid)
                return _oauth_error("invalid_grant", "refresh token 재사용으로 세션을 종료했습니다")
            session = SESSIONS.get(entry.sid)
            if session is None or not session.alive(now):
                return _oauth_error("invalid_grant", "세션이 끝났습니다")
            entry.used = True
            session.last_seen = now
            body = _user_tokens(session, entry.chain, entry.nonce)
        return JSONResponse(body, headers={"Cache-Control": "no-store"})

    if grant == "client_credentials":
        if client is None or client.service_audience is None:
            return _oauth_error("unauthorized_client", "서비스 계정이 없는 클라이언트입니다")
        account = Account(f"service-account-{client.client_id}", "", client.service_roles)
        claims = _base_claims(account, None, client.client_id, client.service_audience, int(now))
        claims["scope"] = "profile"
        return JSONResponse({"access_token": sign(claims), "token_type": "Bearer", "expires_in": ACCESS_TTL,
                             "scope": "profile"}, headers={"Cache-Control": "no-store"})

    if grant == TOKEN_EXCHANGE:
        if client is None:
            return _oauth_error("unauthorized_client", "공개 클라이언트는 토큰 교환을 할 수 없습니다")
        audience = str(form.get("audience", ""))
        if audience not in client.exchange_audiences:
            return _oauth_error("access_denied", f"{client.client_id}는 {audience or '(없음)'} 토큰을 요청할 수 없습니다", 403)
        if form.get("subject_token_type", ACCESS_TOKEN_TYPE) != ACCESS_TOKEN_TYPE:
            return _oauth_error("invalid_request", "access token만 교환합니다")
        try:
            # 교환을 요청하는 클라이언트가 원래 토큰의 대상(aud)이어야 한다.
            subject = verify(str(form.get("subject_token", "")), audience=client.client_id)
        except jwt.PyJWTError as e:
            return _oauth_error("invalid_token", f"subject_token 검증 실패: {e}")
        sid = subject.get("sid")
        if sid:
            with _lock:
                session = SESSIONS.get(sid)
                if session is None or not session.alive(now):
                    return _oauth_error("invalid_token", "원래 토큰의 세션이 끝났습니다")
        claims = {k: subject[k] for k in ("sub", "preferred_username", "realm_access", "sid") if k in subject}
        n = int(now)
        claims.update({"iss": ISSUER, "aud": audience, "azp": client.client_id, "typ": "Bearer", "iat": n,
                       "exp": n + ACCESS_TTL, "jti": secrets.token_hex(12), "scope": subject.get("scope", "profile")})
        return JSONResponse({"access_token": sign(claims), "issued_token_type": ACCESS_TOKEN_TYPE,
                             "token_type": "Bearer", "expires_in": ACCESS_TTL}, headers={"Cache-Control": "no-store"})

    return _oauth_error("unsupported_grant_type", f"지원하지 않는 grant_type: {grant}")


async def logout(request: Request) -> Response:
    params = dict(request.query_params) if request.method == "GET" else dict(await request.form())
    sid = request.cookies.get("TP_SSO")
    hint = params.get("id_token_hint")
    if hint:
        try:
            sid = verify(hint, audience=WEB_CLIENT).get("sid") or sid
        except jwt.ExpiredSignatureError:
            sid = jwt.decode(hint, PUBLIC_KEY, algorithms=["RS256"], audience=WEB_CLIENT, issuer=ISSUER,
                             options={"verify_exp": False}).get("sid") or sid
        except jwt.PyJWTError:
            pass
    if sid:
        with _lock:
            _end_session(sid)
    target = params.get("post_logout_redirect_uri")
    if _redirect_allowed(target):
        if params.get("state"):
            target = f"{target}{'&' if '?' in target else '?'}{urlencode({'state': params['state']})}"
        response: Response = RedirectResponse(target, status_code=302)
    else:
        response = _page("로그아웃", '<h1>로그아웃했습니다</h1><p><a href="/">다시 로그인</a></p>')
    _sso_cookie(request, response, None)
    return response


async def userinfo(request: Request) -> Response:
    header = request.headers.get("authorization", "")
    if not header.lower().startswith("bearer "):
        return _oauth_error("invalid_token", "토큰이 없습니다", 401)
    try:
        claims = verify(header[7:])
    except jwt.PyJWTError as e:
        return _oauth_error("invalid_token", str(e), 401)
    return JSONResponse({"sub": claims["sub"], "preferred_username": claims.get("preferred_username")})


async def health(request: Request) -> Response:
    return PlainTextResponse("ok")


app = Starlette(routes=[
    Route(f"{REALM_PATH}/.well-known/openid-configuration", discovery),
    Route(f"{OIDC}/certs", certs),
    Route(f"{OIDC}/auth", authorize, methods=["GET", "POST"]),
    Route(f"{OIDC}/token", token, methods=["POST"]),
    Route(f"{OIDC}/logout", logout, methods=["GET", "POST"]),
    Route(f"{OIDC}/userinfo", userinfo, methods=["GET", "POST"]),
    Route("/healthz", health),
])


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    if not ACCOUNTS:
        raise SystemExit("DEMO_USER_PASSWORD 또는 ADMIN_PASSWORD 중 하나는 있어야 합니다.")
    log.info("demo-idp issuer=%s accounts=%s clients=%s", ISSUER, sorted(ACCOUNTS), sorted(CLIENTS))
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8080")), proxy_headers=True,
                forwarded_allow_ips="*", access_log=False, log_level="warning")
