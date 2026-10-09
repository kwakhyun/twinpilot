// 실행 시 설정. 컨테이너에서는 nginx가 환경 변수로 /config.json을 만들어 준다(이미지 재빌드 없이 환경별 설정).

export interface RuntimeConfig {
  auth: { mode: "oidc" | "none"; authority?: string; clientId?: string };
}

let current: RuntimeConfig | null = null;

export async function loadConfig(): Promise<RuntimeConfig> {
  if (current) return current;
  const res = await fetch("/config.json", { cache: "no-store" });
  if (!res.ok) throw new Error(`설정을 불러오지 못했습니다(${res.status})`);
  const cfg = (await res.json()) as RuntimeConfig;
  if (cfg.auth.mode === "oidc" && (!cfg.auth.authority || !cfg.auth.clientId)) {
    throw new Error("OIDC 설정(authority, clientId)이 비어 있습니다.");
  }
  current = cfg;
  return cfg;
}
