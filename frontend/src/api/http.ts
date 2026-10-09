// 인증 헤더를 붙이는 fetch. 401이면 토큰을 한 번 갱신해 다시 보내고, 그래도 401이면 로그인 화면으로 보낸다.
import { accessToken, authEnabled, login, renew } from "../auth/session";

export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const send = async (token: string | null) => {
    const headers = new Headers(init.headers);
    if (token) headers.set("Authorization", `Bearer ${token}`);
    return fetch(input, { ...init, headers });
  };
  let res = await send(await accessToken());
  if (res.status === 401 && authEnabled()) {
    const user = await renew();
    if (user) res = await send(user.access_token);
    if (res.status === 401) {
      void login();
    }
  }
  return res;
}

/** RFC 9457 ProblemDetail(detail) 또는 일반 오류 본문에서 사용자에게 보여 줄 메시지를 꺼낸다. */
export async function errorMessage(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body.detail ?? body.error ?? res.statusText;
  } catch {
    return res.statusText || `HTTP ${res.status}`;
  }
}
