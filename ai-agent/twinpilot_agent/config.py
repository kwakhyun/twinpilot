from __future__ import annotations

import os
from dataclasses import dataclass, field


def _has_anthropic_credentials() -> bool:
    return bool(os.getenv("ANTHROPIC_API_KEY") or os.getenv("ANTHROPIC_AUTH_TOKEN"))


@dataclass(frozen=True)
class Settings:
    """환경 변수 기반 설정.

    AGENT_MODE
      - auto: Anthropic 자격 증명이 있으면 llm, 없으면 rule
      - llm : LangChain ChatAnthropic(공식 anthropic SDK) + MCP 도구
      - rule: 규칙 기반 플래너(오프라인 데모·테스트용). 같은 그래프·MCP 도구 경로를 탄다.
    """

    mcp_url: str = field(default_factory=lambda: os.getenv("MCP_URL", "http://localhost:8090/mcp"))
    mode: str = field(default_factory=lambda: os.getenv("AGENT_MODE", "auto"))
    model: str = field(default_factory=lambda: os.getenv("LLM_MODEL", "claude-opus-5-5"))
    effort: str = field(default_factory=lambda: os.getenv("LLM_EFFORT", "medium"))
    max_tokens: int = field(default_factory=lambda: int(os.getenv("LLM_MAX_TOKENS", "16000")))
    # 서버 측 거절(refusal) 폴백: claude-opus-5-5 권장 기본값. 지원하지 않는 플랫폼에서는 끈다.
    server_fallbacks: bool = field(default_factory=lambda: os.getenv("LLM_SERVER_FALLBACKS", "true") == "true")
    max_tool_rounds: int = field(default_factory=lambda: int(os.getenv("AGENT_MAX_TOOL_ROUNDS", "5")))
    # 지정하면 대화 상태를 PostgreSQL에 저장해 여러 레플리카가 세션을 공유한다(미지정 시 메모리).
    checkpoint_db_url: str | None = field(default_factory=lambda: os.getenv("CHECKPOINT_DB_URL") or None)
    # 대화 기록 보존 일수(PostgreSQL 저장소일 때 주기적으로 정리)
    checkpoint_retention_days: int = field(default_factory=lambda: int(os.getenv("CHECKPOINT_RETENTION_DAYS", "30")))
    # 사용자별 요청 한도(분당, 순간 허용량). 레플리카마다 따로 센다.
    rate_per_minute: float = field(default_factory=lambda: float(os.getenv("AGENT_RATE_PER_MIN", "20")))
    rate_burst: int = field(default_factory=lambda: int(os.getenv("AGENT_RATE_BURST", "5")))
    # 한 턴 전체 제한 시간(초)
    request_timeout_s: float = field(default_factory=lambda: float(os.getenv("AGENT_REQUEST_TIMEOUT_S", "180")))
    # LLM 호출이 재시도 후에도 실패하면 규칙 기반 플래너로 같은 턴을 이어서 답한다.
    llm_fallback: bool = field(default_factory=lambda: os.getenv("AGENT_LLM_FALLBACK", "true") == "true")

    @property
    def resolved_mode(self) -> str:
        if self.mode == "auto":
            return "llm" if _has_anthropic_credentials() else "rule"
        return self.mode
