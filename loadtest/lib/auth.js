// Keycloak 로그인(Authorization Code + PKCE)과 토큰 갱신. 브라우저와 같은 경로로 토큰을 받는다.
//   PUBLIC_BASE  : 토큰 iss·redirect_uri에 쓰이는 공개 주소(예: http://localhost:8088)
//   AUTH_BASE    : k6가 실제로 접속하는 주소(같은 compose 네트워크면 http://frontend:8080)
// Keycloak은 공개 주소로 로그인 폼 URL을 만들므로, 그 URL의 앞부분을 AUTH_BASE로 바꿔 요청하고
// Host 헤더는 공개 주소로 보낸다. 세션 쿠키는 Secure 속성이라 http에서는 직접 넘긴다.
// 로그인 요청은 매번 빈 쿠키 저장소로 보낸다(VU 기본 저장소에 남은 다른 계정의 SSO 쿠키가 섞이지 않게).
import http from "k6/http";
import crypto from "k6/crypto";
import encoding from "k6/encoding";
import { fail } from "k6";

export const PUBLIC_BASE = (__ENV.PUBLIC_BASE || "http://localhost:8088").replace(/\/$/, "");
export const AUTH_BASE = (__ENV.AUTH_BASE || PUBLIC_BASE).replace(/\/$/, "");
const REALM = __ENV.REALM || "twinpilot";
const CLIENT_ID = __ENV.CLIENT_ID || "twinpilot-web";
const publicHost = PUBLIC_BASE.replace(/^https?:\/\//, "");
const hostHeader = AUTH_BASE === PUBLIC_BASE ? {} : { Host: publicHost };
const realmPath = `/auth/realms/${REALM}/protocol/openid-connect`;

const internal = (url) => (url.startsWith(PUBLIC_BASE) ? AUTH_BASE + url.slice(PUBLIC_BASE.length) : url);
const unescapeHtml = (s) => s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;/g, "'");
const tagged = { tags: { name: "auth" } };

const randomUrlSafe = (bytes) => encoding.b64encode(crypto.randomBytes(bytes), "rawurl");

function cookieMap(res) {
  const out = {};
  for (const [name, values] of Object.entries(res.cookies)) {
    if (values.length) out[name] = values[0].value;
  }
  return out;
}

function authorize(cookies) {
  const verifier = randomUrlSafe(48);
  const challenge = crypto.sha256(verifier, "base64rawurl");
  const query = [
    `client_id=${CLIENT_ID}`, "response_type=code", "scope=openid%20profile",
    `redirect_uri=${encodeURIComponent(`${PUBLIC_BASE}/`)}`, `state=${randomUrlSafe(16)}`,
    `code_challenge=${challenge}`, "code_challenge_method=S256",
  ].join("&");
  const res = http.get(`${AUTH_BASE}${realmPath}/auth?${query}`, {
    headers: hostHeader, cookies, jar: new http.CookieJar(), redirects: 0, ...tagged,
    responseCallback: http.expectedStatuses(200, 302),
  });
  return { res, verifier };
}

function exchange(res, verifier, who) {
  const location = res.headers.Location || res.headers.location || "";
  const code = /[?&]code=([^&]+)/.exec(location);
  if (res.status !== 302 || !code) fail(`인가 코드를 받지 못했다(${who}): ${res.status}`);
  return tokenRequest({
    grant_type: "authorization_code", code: decodeURIComponent(code[1]), redirect_uri: `${PUBLIC_BASE}/`,
    client_id: CLIENT_ID, code_verifier: verifier,
  });
}

/** 비밀번호 로그인(브라우저 첫 로그인). SSO 쿠키를 함께 돌려준다. */
export function login(username, password) {
  const { res: page, verifier } = authorize({});
  const form = /<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"/.exec(page.body || "");
  if (page.status !== 200 || !form) fail(`로그인 폼을 받지 못했다: ${page.status}`);
  const submit = http.post(internal(unescapeHtml(form[1])), { username, password, credentialId: "" }, {
    headers: hostHeader, cookies: cookieMap(page), jar: new http.CookieJar(), redirects: 0, ...tagged,
    responseCallback: http.expectedStatuses(302), // 성공하면 redirect_uri로 302
  });
  return { sso: cookieMap(submit), token: exchange(submit, verifier, username) };
}

function tokenRequest(body) {
  const res = http.post(`${AUTH_BASE}${realmPath}/token`, body, { headers: hostHeader, ...tagged });
  if (res.status !== 200) fail(`토큰 요청 실패: ${res.status} ${res.body}`);
  const t = res.json();
  return {
    accessToken: t.access_token,
    refreshToken: t.refresh_token,
    expiresAt: Date.now() + (t.expires_in - 30) * 1000, // 30초 여유를 두고 갱신
  };
}

/**
 * setup()에서 사용자마다 한 번만 비밀번호로 로그인하고 SSO 쿠키를 VU들에 넘긴다.
 * 같은 계정으로 수십 번 동시에 비밀번호 로그인하면 Keycloak 무차별 대입 방어가 계정을 잠시 잠근다.
 */
export function loginAll(usernames, password) {
  const sso = {};
  for (const u of usernames) sso[u] = login(u, password).sso;
  return sso;
}

/**
 * VU별 세션 = 브라우저 탭. SSO 쿠키로 비밀번호 없이 인가 코드를 받아 자기 토큰 체인을 갖고, 만료 30초 전에 갱신한다.
 * (realm은 refresh token 회전을 켜 두어 같은 refresh token을 두 번 쓰면 세션을 끊는다. 탭·VU끼리 토큰을 나눠 쓰면 안 된다.)
 */
export class Session {
  constructor(sso, who = "user") {
    this.sso = sso;
    this.who = who;
    this.token = null;
  }

  authHeader() {
    if (!this.token) {
      const { res, verifier } = authorize(this.sso);
      this.token = exchange(res, verifier, this.who);
    } else if (Date.now() >= this.token.expiresAt) {
      this.token = tokenRequest({ grant_type: "refresh_token", refresh_token: this.token.refreshToken, client_id: CLIENT_ID });
    }
    return { Authorization: `Bearer ${this.token.accessToken}` };
  }
}
