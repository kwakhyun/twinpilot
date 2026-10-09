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

    @property
    def resolved_mode(self) -> str:
        if self.mode == "auto":
            return "llm" if _has_anthropic_credentials() else "rule"
        return self.mode
