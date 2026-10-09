# syntax=docker/dockerfile:1.7
# frontend: Vite 빌드 → nginx 정적 서빙 + /api·/agent 리버스 프록시
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
# TLS 검사 프록시 뒤에서 빌드할 때만 CA 번들을 주입한다: --secret id=extra_ca,src=ca.pem
RUN --mount=type=cache,target=/root/.npm \
    --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi; \
    npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# 비루트(uid 101) nginx: Kubernetes에서 runAsNonRoot로 실행한다.
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
# 런타임 환경 변수로 업스트림 주소를 바꾼다(docker-compose·Kubernetes 공용)
ENV TWIN_API_UPSTREAM=twin-api:8080 \
    AGENT_UPSTREAM=ai-agent:8000
EXPOSE 8080
