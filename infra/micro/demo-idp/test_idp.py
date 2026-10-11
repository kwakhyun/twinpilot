"""demo-idp 동작 테스트. mcp-server 이미지(같은 라이브러리)에서 실행한다.

    docker run --rm -v $PWD/infra/micro/demo-idp:/idp:ro -w /idp -e PYTHONDONTWRITEBYTECODE=1 \
      twinpilot/mcp-server:0.2.0 python -m unittest -v test_idp
"""
import base64
import hashlib
import os
import secrets
import tempfile
import unittest
from urllib.parse import parse_qs, urlparse

os.environ.update({
    "PUBLIC_URL": "https://demo.test", "DEMO_USER_PASSWORD": "demo-pw", "ADMIN_PASSWORD": "",
    "AGENT_CLIENT_SECRET": "agent-s", "MCP_CLIENT_SECRET": "mcp-s", "SENSOR_GATEWAY_SECRET": "sensor-s",
    "KEY_FILE": os.path.join(tempfile.mkdtemp(), "key.pem"),
})

import jwt  # noqa: E402
from starlette.testclient import TestClient  # noqa: E402

import idp  # noqa: E402

AUTH = "/auth/realms/twinpilot/protocol/openid-connect"
REDIRECT = "https://demo.test/"


def pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


class DemoIdpTest(unittest.TestCase):
    def setUp(self):
        for store in (idp.SESSIONS, idp.PENDING, idp.CODES, idp.REFRESH, idp.FAILURES):
            store.clear()
        self.client = TestClient(idp.app, base_url="https://demo.test", follow_redirects=False)

    def authorize(self, **overrides):
        verifier, challenge = pkce()
        params = {"client_id": "twinpilot-web", "redirect_uri": REDIRECT, "response_type": "code",
                  "scope": "openid profile", "state": "st", "nonce": "nn", "code_challenge": challenge,
                  "code_challenge_method": "S256", **overrides}
        return self.client.get(f"{AUTH}/auth", params=params), verifier

    def login(self, username="planner", password="demo-pw"):
        page, verifier = self.authorize()
        auth_id = page.text.split('name="auth_id" value="')[1].split('"')[0]
        res = self.client.post(f"{AUTH}/auth", data={"auth_id": auth_id, "username": username, "password": password})
        return res, verifier

    def tokens(self, username="planner"):
        res, verifier = self.login(username)
        self.assertEqual(res.status_code, 302)
        q = parse_qs(urlparse(res.headers["location"]).query)
        self.assertEqual(q["state"], ["st"])
        tok = self.client.post(f"{AUTH}/token", data={"grant_type": "authorization_code", "client_id": "twinpilot-web",
                                                      "code": q["code"][0], "redirect_uri": REDIRECT,
                                                      "code_verifier": verifier})
        self.assertEqual(tok.status_code, 200, tok.text)
        return tok.json()

    def test_login_issues_keycloak_shaped_tokens(self):
        body = self.tokens("planner")
        claims = jwt.decode(body["access_token"], idp.PUBLIC_KEY, algorithms=["RS256"], audience="twinpilot-api",
                            issuer="https://demo.test/auth/realms/twinpilot")
        self.assertEqual(claims["preferred_username"], "planner")
        self.assertEqual(claims["realm_access"]["roles"], ["PLANNER"])
        self.assertEqual(sorted(claims["aud"]), ["twinpilot-agent", "twinpilot-api"])
        id_token = jwt.decode(body["id_token"], idp.PUBLIC_KEY, algorithms=["RS256"], audience="twinpilot-web",
                              issuer=idp.ISSUER)
        self.assertEqual(id_token["nonce"], "nn")

    def test_code_is_single_use_and_pkce_checked(self):
        res, verifier = self.login()
        code = parse_qs(urlparse(res.headers["location"]).query)["code"][0]
        form = {"grant_type": "authorization_code", "client_id": "twinpilot-web", "code": code,
                "redirect_uri": REDIRECT, "code_verifier": "wrong-verifier"}
        self.assertEqual(self.client.post(f"{AUTH}/token", data=form).json()["error"], "invalid_grant")
        form["code_verifier"] = verifier   # 실패한 시도로 코드가 소모됐다
        self.assertEqual(self.client.post(f"{AUTH}/token", data=form).json()["error"], "invalid_grant")

    def test_rejects_foreign_redirect_and_missing_pkce(self):
        res, _ = self.authorize(redirect_uri="https://evil.test/")
        self.assertEqual(res.status_code, 400)
        res, _ = self.authorize(code_challenge_method="plain")
        self.assertIn("error=invalid_request", res.headers["location"])

    def test_sso_cookie_skips_form(self):
        self.tokens()
        res, _ = self.authorize()
        self.assertEqual(res.status_code, 302)
        self.assertIn("code=", res.headers["location"])

    def test_refresh_rotation_and_reuse_detection(self):
        first = self.tokens()["refresh_token"]
        form = {"grant_type": "refresh_token", "client_id": "twinpilot-web", "refresh_token": first}
        second = self.client.post(f"{AUTH}/token", data=form)
        self.assertEqual(second.status_code, 200)
        reuse = self.client.post(f"{AUTH}/token", data=form)
        self.assertEqual(reuse.json()["error"], "invalid_grant")
        # 재사용을 감지하면 세션 전체를 끝내므로 새로 받은 토큰도 더는 갱신되지 않는다.
        after = self.client.post(f"{AUTH}/token", data={**form, "refresh_token": second.json()["refresh_token"]})
        self.assertEqual(after.json()["error"], "invalid_grant")

    def test_wrong_password_and_lockout(self):
        for _ in range(5):
            res, _ = self.login(password="nope")
            self.assertEqual(res.status_code, 401)
        res, _ = self.login()   # 올바른 비밀번호라도 잠금 중에는 막는다
        self.assertEqual(res.status_code, 429)

    def test_admin_disabled_without_password(self):
        res, _ = self.login(username="admin", password="")
        self.assertEqual(res.status_code, 401)

    def test_client_credentials(self):
        ok = self.client.post(f"{AUTH}/token", data={"grant_type": "client_credentials"}, auth=("twinpilot-mcp", "mcp-s"))
        claims = jwt.decode(ok.json()["access_token"], idp.PUBLIC_KEY, algorithms=["RS256"], audience="twinpilot-api",
                            issuer=idp.ISSUER)
        self.assertEqual(claims["preferred_username"], "service-account-twinpilot-mcp")
        self.assertEqual(claims["realm_access"]["roles"], ["ANALYST"])
        bad = self.client.post(f"{AUTH}/token", data={"grant_type": "client_credentials"}, auth=("twinpilot-mcp", "x"))
        self.assertEqual(bad.status_code, 401)

    def test_token_exchange_chain_and_audience_rules(self):
        user = self.tokens("viewer")["access_token"]
        ex = {"grant_type": idp.TOKEN_EXCHANGE, "subject_token": user, "audience": "twinpilot-mcp",
              "subject_token_type": idp.ACCESS_TOKEN_TYPE, "requested_token_type": idp.ACCESS_TOKEN_TYPE}
        to_mcp = self.client.post(f"{AUTH}/token", data=ex, auth=("twinpilot-agent", "agent-s"))
        self.assertEqual(to_mcp.status_code, 200, to_mcp.text)
        mcp_token = to_mcp.json()["access_token"]
        to_api = self.client.post(f"{AUTH}/token", data={**ex, "subject_token": mcp_token, "audience": "twinpilot-api"},
                                  auth=("twinpilot-mcp", "mcp-s"))
        claims = jwt.decode(to_api.json()["access_token"], idp.PUBLIC_KEY, algorithms=["RS256"],
                            audience="twinpilot-api", issuer=idp.ISSUER)
        self.assertEqual((claims["preferred_username"], claims["realm_access"]["roles"], claims["azp"]),
                         ("viewer", ["VIEWER"], "twinpilot-mcp"))
        # 에이전트는 twin-api 토큰을 직접 받을 수 없다(MCP를 거쳐야 한다).
        denied = self.client.post(f"{AUTH}/token", data={**ex, "audience": "twinpilot-api"},
                                  auth=("twinpilot-agent", "agent-s"))
        self.assertEqual(denied.status_code, 403)
        # MCP는 자기가 대상이 아닌 토큰(브라우저 토큰)을 교환할 수 없다.
        not_aud = self.client.post(f"{AUTH}/token", data={**ex, "audience": "twinpilot-api"},
                                   auth=("twinpilot-mcp", "mcp-s"))
        self.assertEqual(not_aud.json()["error"], "invalid_token")

    def test_logout_ends_session(self):
        body = self.tokens()
        res = self.client.get(f"{AUTH}/logout", params={"id_token_hint": body["id_token"],
                                                        "post_logout_redirect_uri": REDIRECT})
        self.assertEqual(res.status_code, 302)
        refresh = self.client.post(f"{AUTH}/token", data={"grant_type": "refresh_token", "client_id": "twinpilot-web",
                                                          "refresh_token": body["refresh_token"]})
        self.assertEqual(refresh.json()["error"], "invalid_grant")


if __name__ == "__main__":
    unittest.main()
