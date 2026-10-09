# syntax=docker/dockerfile:1.7
FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY pyproject.toml ./
COPY twinpilot_agent twinpilot_agent
# TLS 검사 프록시(사내망 등) 뒤에서 빌드할 때만 CA 번들을 주입한다: --secret id=extra_ca,src=ca.pem
RUN --mount=type=cache,target=/root/.cache/pip \
    --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export PIP_CERT=/run/secrets/extra_ca; fi; \
    pip install .
RUN useradd -r -u 10001 app
USER 10001
# ANTHROPIC_API_KEY가 있으면 LLM 모드, 없으면 규칙 기반 플래너로 동작한다(AGENT_MODE=auto).
ENV MCP_URL=http://mcp-server:8090/mcp AGENT_MODE=auto LLM_MODEL=claude-opus-5-5
EXPOSE 8000
CMD ["uvicorn", "twinpilot_agent.app:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
