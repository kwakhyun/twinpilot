# syntax=docker/dockerfile:1.7
# frontend: Vite 빌드 → nginx 정적 서빙 + /api·/agent·/auth 리버스 프록시
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
# TLS 검사 프록시 뒤에서 빌드할 때만 CA 번들을 주입한다: --secret id=extra_ca,src=ca.pem
RUN --mount=type=cache,target=/root/.npm \
    --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi; \
    npm ci --no-audit --no-fund
COPY . .
RUN npm run build && rm -f dist/config.json   # 실행 설정은 nginx가 환경 변수로 제공한다

# 비루트(uid 101) nginx: Kubernetes에서 runAsNonRoot·readOnlyRootFilesystem으로 실행한다.
FROM nginxinc/nginx-unprivileged:1.30-alpine
# 베이스 이미지 이후 나온 보안 패치를 반영한다(CI 기본값). 패키지 저장소에 닿지 않는 환경에서는 APK_UPGRADE=false.
ARG APK_UPGRADE=true
USER root
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ "$APK_UPGRADE" = "true" ]; then \
      if [ -s /run/secrets/extra_ca ]; then export SSL_CERT_FILE=/run/secrets/extra_ca; fi; \
      apk upgrade --no-cache; \
    fi; \
    apk del --no-cache curl
USER 101
COPY nginx/ /etc/nginx/templates/
COPY --from=build /src/dist /usr/share/nginx/html
# 런타임 환경 변수(docker-compose·Kubernetes 공용). OIDC_AUTHORITY는 브라우저가 접근하는 공개 주소다.
ENV TWIN_API_UPSTREAM=twin-api:8080 \
    AGENT_UPSTREAM=ai-agent:8000 \
    KEYCLOAK_UPSTREAM=keycloak:8080 \
    AUTH_MODE=oidc \
    OIDC_AUTHORITY=http://localhost:8088/auth/realms/twinpilot \
    OIDC_CLIENT_ID=twinpilot-web \
    CSP_CONNECT_EXTRA="" \
    RATE_LIMIT_API=30r/s \
    RATE_LIMIT_AGENT=2r/s \
    NGINX_ENVSUBST_FILTER="^(TWIN_API_UPSTREAM|AGENT_UPSTREAM|KEYCLOAK_UPSTREAM|AUTH_MODE|OIDC_AUTHORITY|OIDC_CLIENT_ID|CSP_CONNECT_EXTRA|RATE_LIMIT_API|RATE_LIMIT_AGENT)$"
EXPOSE 8080
