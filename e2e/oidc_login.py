"""헤드리스 OIDC 로그인(Authorization Code + PKCE) — 개발·부하 테스트용.

웹 클라이언트(twinpilot-web)는 비밀번호 직접 교환(password grant)을 막아 두었으므로,
브라우저와 같은 경로(로그인 폼 제출 → 인가 코드 → 토큰 교환)로 액세스 토큰을 받는다.

    python e2e/oidc_login.py --base http://localhost:8088 --user analyst --password ...
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import html
import re
import secrets
import sys
from urllib.parse import parse_qs, urlencode, urljoin, urlparse

import httpx


def login(base: str, username: str, password: str, *, realm: str = "twinpilot", client_id: str = "twinpilot-web",
          issuer: str | None = None, timeout: float = 15.0) -> dict:
    issuer = issuer or f"{base.rstrip('/')}/auth/realms/{realm}"
    redirect_uri = f"{base.rstrip('/')}/"
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    params = {"client_id": client_id, "response_type": "code", "scope": "openid profile",
              "redirect_uri": redirect_uri, "state": state, "code_challenge": challenge,
              "code_challenge_method": "S256"}
    with httpx.Client(timeout=timeout, follow_redirects=False) as client:
        page = client.get(f"{issuer}/protocol/openid-connect/auth?{urlencode(params)}")
        page.raise_for_status()
        match = re.search(r'<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"', page.text)
        if not match:
            raise RuntimeError("로그인 폼을 찾지 못했습니다.")
        action = urljoin(str(page.url), html.unescape(match.group(1)))
        # Keycloak 세션 쿠키는 Secure 속성이라 http(localhost 개발 환경)에서는 httpx가 보내지 않는다.
        # 브라우저는 localhost를 안전한 출처로 보아 보내므로 같은 동작을 위해 직접 싣는다.
        cookie = "; ".join(f"{c.name}={c.value}" for c in client.cookies.jar)
        res = client.post(action, data={"username": username, "password": password, "credentialId": ""},
                          headers={"Cookie": cookie})
        if res.status_code != 302:
            raise RuntimeError(f"로그인 실패(HTTP {res.status_code}): 사용자 이름·비밀번호를 확인하세요.")
        location = res.headers["location"]
        query = parse_qs(urlparse(location).query)
        if query.get("state", [None])[0] != state or "code" not in query:
            raise RuntimeError(f"인가 코드가 없습니다: {location}")
        token = client.post(f"{issuer}/protocol/openid-connect/token", data={
            "grant_type": "authorization_code", "client_id": client_id, "code": query["code"][0],
            "redirect_uri": redirect_uri, "code_verifier": verifier})
        token.raise_for_status()
        return token.json()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", default="http://localhost:8088")
    parser.add_argument("--issuer", default=None, help="기본값: {base}/auth/realms/twinpilot")
    parser.add_argument("--user", required=True)
    parser.add_argument("--password", required=True)
    args = parser.parse_args()
    try:
        print(login(args.base, args.user, args.password, issuer=args.issuer)["access_token"])
    except Exception as e:  # noqa: BLE001 — CLI 오류 메시지
        print(e, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
