// 로그인 세션. oidc-client-ts로 Authorization Code + PKCE 흐름을 처리한다.
// 토큰은 sessionStorage에 두고(탭을 닫으면 사라짐) 만료 전 refresh token으로 조용히 갱신한다.
import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";
import { create } from "zustand";
import type { RuntimeConfig } from "../config";

export type Role = "VIEWER" | "ANALYST" | "PLANNER" | "ADMIN";
const RANK: Record<Role, number> = { VIEWER: 1, ANALYST: 2, PLANNER: 3, ADMIN: 4 };

interface SessionState {
  status: "loading" | "ready" | "error";
  username: string | null;
  roles: string[];
  error: string | null;
  set: (p: Partial<Omit<SessionState, "set">>) => void;
}

export const useSession = create<SessionState>((set) => ({
  status: "loading",
  username: null,
  roles: [],
  error: null,
  set: (p) => set(p),
}));

/** 서버가 최종 판단하지만, 화면에서도 권한 없는 버튼을 숨기거나 끈다. */
export function hasRole(roles: string[], role: Role): boolean {
  const mine = Math.max(0, ...roles.map((r) => RANK[r as Role] ?? 0));
  return roles.includes(role) || mine >= RANK[role];
}

export function useCan(role: Role): boolean {
  return useSession((s) => hasRole(s.roles, role));
}

let manager: UserManager | null = null;
let renewing: Promise<User | null> | null = null;

export async function initSession(cfg: RuntimeConfig): Promise<void> {
  if (cfg.auth.mode !== "oidc") {
    useSession.getState().set({ status: "ready", username: "dev", roles: ["ADMIN"] });
    return;
  }
  const origin = window.location.origin;
  manager = new UserManager({
    authority: cfg.auth.authority!,
    client_id: cfg.auth.clientId!,
    redirect_uri: `${origin}/`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: "code",
    scope: "openid profile",
    automaticSilentRenew: true,
    monitorSession: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
  manager.events.addSilentRenewError(() => void login());

  const params = new URLSearchParams(window.location.search);
  if (params.has("code") && params.has("state")) {
    const user = await manager.signinRedirectCallback();
    const returnTo = (user.state as { returnTo?: string } | undefined)?.returnTo ?? "/";
    window.history.replaceState(null, "", returnTo);
  } else if (params.has("error")) {
    throw new Error(`로그인 실패: ${params.get("error_description") ?? params.get("error")}`);
  }
  const user = await manager.getUser();
  if (!user || user.expired) {
    await login();
    return new Promise(() => {}); // 로그인 페이지로 이동 중
  }
}

export async function login(): Promise<void> {
  await manager?.signinRedirect({ state: { returnTo: window.location.pathname + window.location.search } });
}

export async function logout(): Promise<void> {
  if (!manager) return;
  const user = await manager.getUser();
  await manager.signoutRedirect({ id_token_hint: user?.id_token });
}

/** 유효한 액세스 토큰. 만료됐으면 갱신을 시도하고, 실패하면 null(호출자가 로그인으로 보낸다). */
export async function accessToken(): Promise<string | null> {
  if (!manager) return null;
  const user = await manager.getUser();
  if (user && !user.expired && (user.expires_in ?? 0) > 15) return user.access_token;
  return (await renew())?.access_token ?? null;
}

/** 동시에 여러 요청이 401을 받아도 갱신은 한 번만 한다. */
export async function renew(): Promise<User | null> {
  if (!manager) return null;
  renewing ??= manager.signinSilent().catch(() => null).finally(() => {
    renewing = null;
  });
  return renewing;
}

export function authEnabled(): boolean {
  return manager !== null;
}
