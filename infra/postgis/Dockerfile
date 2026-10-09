# syntax=docker/dockerfile:1.7
# PostGIS(PostgreSQL 16 + PostGIS 3.5) 운영 이미지
# - 공개 이미지(postgis/postgis:16-3.5*)는 기반 패키지·gosu(Go 표준 라이브러리) 취약점이 남아 있어 그대로 쓰지 않는다.
# - 항상 postgres 사용자(uid·gid 70)로 실행하므로 루트에서 권한을 낮출 때만 쓰는 gosu를 지운다.
#   (Kubernetes: runAsUser 70, docker compose: 이미지 USER)
# - 베이스 이후 나온 보안 패치를 반영한다(CI 기본값). 패키지 저장소에 닿지 않는 환경에서는 APK_UPGRADE=false.
FROM postgis/postgis:16-3.5-alpine
ARG APK_UPGRADE=true
USER root
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ "$APK_UPGRADE" = "true" ]; then \
      if [ -s /run/secrets/extra_ca ]; then export SSL_CERT_FILE=/run/secrets/extra_ca; fi; \
      apk upgrade --no-cache; \
    fi; \
    rm -f /usr/local/bin/gosu /usr/local/bin/su-exec
USER postgres
