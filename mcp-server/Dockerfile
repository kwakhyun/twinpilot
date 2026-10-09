# syntax=docker/dockerfile:1.7
# 의존성은 해시를 고정한 requirements.lock(uv pip compile --generate-hashes)으로 재현 가능하게 설치하고,
# 실행 이미지에는 가상환경만 복사한다(빌드 도구·pip 캐시 없음).
FROM python:3.13-slim AS build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1
RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
WORKDIR /src
COPY requirements.lock ./
# TLS 검사 프록시(사내망 등) 뒤에서 빌드할 때만 CA 번들을 주입한다: --secret id=extra_ca,src=ca.pem
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export PIP_CERT=/run/secrets/extra_ca; fi; \
    pip install --require-hashes -r requirements.lock
COPY pyproject.toml ./
COPY twinpilot_mcp twinpilot_mcp
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export PIP_CERT=/run/secrets/extra_ca; fi; \
    pip install --no-deps .

FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PATH=/opt/venv/bin:$PATH LANG=C.UTF-8
RUN useradd -r -u 10001 app
COPY --from=build /opt/venv /opt/venv
USER 10001
ENV MCP_HOST=0.0.0.0 MCP_PORT=8090 TWIN_API_URL=http://twin-api:8080 FORWARDED_ALLOW_IPS=*
EXPOSE 8090
CMD ["python", "-m", "twinpilot_mcp", "--transport", "streamable-http"]
