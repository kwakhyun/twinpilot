"""LLM 구성. LangChain ChatAnthropic은 내부적으로 공식 anthropic SDK를 사용한다."""
from __future__ import annotations

from langchain_anthropic import ChatAnthropic

from .config import Settings

SYSTEM_PROMPT = """너는 TwinPilot의 디지털트윈 안전 분석 코파일럿이다. 사용자는 지자체 안전·소방 담당자다.

- 공간 분석은 제공된 도구로 수행하고, 답변의 수치는 도구 결과에서만 인용한다.
- 서로 독립적인 조회는 한 번에 함께 호출한다.
- 도구 결과는 사용자 화면의 3D 지도에 자동으로 표시된다. 지도에서 확인할 수 있다고 안내해도 된다.
- 행정동은 이름(예: 다솜동)으로 지정하면 된다. 모르는 지역이면 list_regions로 확인한다.
- 답변은 한국어로, 짧은 요약 → 핵심 수치 목록 → 다음 조치 제안 순서로 쓴다. 적용한 분석 기준(법정 기준 또는 가정값)을 한 줄로 밝힌다.
- 도구로 확인할 수 없는 내용(실제 주소, 예산, 공사 일정 등)은 추측하지 말고 확인할 수 없다고 말한다.
- 데이터는 합성(가상) 데모 데이터라는 점을 답변 끝에 한 줄로 알린다."""


def build_chat_model(settings: Settings) -> ChatAnthropic:
    """claude-opus-5-5: 사고(adaptive thinking)는 항상 켜져 있고 effort로 깊이를 조절한다(기본 medium).
    강제 tool_choice(any/tool)는 지원되지 않으므로 기본값 auto를 쓴다."""
    kwargs: dict = {}
    if settings.server_fallbacks:
        # 안전 분류기가 요청을 거절하면 서버가 거절 범주에 맞는 모델로 같은 요청을 다시 실행한다.
        kwargs["betas"] = ["server-side-fallback-2026-07-01"]
        kwargs["model_kwargs"] = {"fallbacks": "default"}
    return ChatAnthropic(
        model=settings.model,
        max_tokens=settings.max_tokens,
        effort=settings.effort,
        max_retries=2,
        timeout=120,
        **kwargs,
    )
