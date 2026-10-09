import { useEffect, type ReactNode } from "react";
import { api } from "../api/client";
import { loadConfig } from "../config";
import { hasRole, initSession, login, useSession } from "./session";

/** 설정 로드 → 로그인(필요하면 IdP로 이동) → 서버에서 내 역할 확인 후 앱을 그린다. */
export function AuthGate({ children }: { children: ReactNode }) {
  const status = useSession((s) => s.status);
  const error = useSession((s) => s.error);
  const roles = useSession((s) => s.roles);
  const set = useSession((s) => s.set);

  useEffect(() => {
    (async () => {
      try {
        const cfg = await loadConfig();
        await initSession(cfg);
        if (cfg.auth.mode === "oidc") {
          const me = await api.me();
          set({ status: "ready", username: me.username, roles: me.roles });
        }
      } catch (e) {
        set({ status: "error", error: (e as Error).message });
      }
    })();
  }, []);

  if (status === "loading") {
    return (
      <div className="gate" role="status">
        <span className="logo" aria-hidden>
          ◆
        </span>{" "}
        TwinPilot 로그인 확인 중…
      </div>
    );
  }
  if (status === "error") {
    return (
      <div className="gate" role="alert">
        <p>{error}</p>
        <button type="button" className="btn primary" onClick={() => void login()}>
          다시 로그인
        </button>
      </div>
    );
  }
  if (!hasRole(roles, "VIEWER")) {
    return (
      <div className="gate" role="alert">
        <p>TwinPilot을 사용할 권한이 없습니다. 관리자에게 역할(VIEWER 이상) 부여를 요청하세요.</p>
      </div>
    );
  }
  return <>{children}</>;
}
