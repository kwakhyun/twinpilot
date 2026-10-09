# syntax=docker/dockerfile:1.7
FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY pyproject.toml ./
COPY twinpilot_mcp twinpilot_mcp
# TLS 검사 프록시(사내망 등) 뒤에서 빌드할 때만 CA 번들을 주입한다: --secret id=extra_ca,src=ca.pem
RUN --mount=type=cache,target=/root/.cache/pip \
    --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export PIP_CERT=/run/secrets/extra_ca; fi; \
    pip install .
RUN useradd -r -u 10001 app
USER 10001
ENV MCP_HOST=0.0.0.0 MCP_PORT=8090 TWIN_API_URL=http://twin-api:8080
EXPOSE 8090
CMD ["python", "-m", "twinpilot_mcp", "--transport", "streamable-http"]
