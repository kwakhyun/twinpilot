# syntax=docker/dockerfile:1.7
# Keycloak 최적화 이미지: 빌드 옵션(DB 종류·상대 경로·헬스·메트릭)을 미리 굳혀 'start --optimized'로 빠르게 기동하고,
# 실행 중에 Quarkus 재빌드를 하지 않아 읽기 전용 루트 파일시스템으로 돌릴 수 있다.
# realm 정의(역할·클라이언트, 사용자 없음)를 이미지에 포함한다. 시크릿 값은 환경 변수 자리표시자(${...})로 받는다.
FROM quay.io/keycloak/keycloak:26.6 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_HTTP_RELATIVE_PATH=/auth
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.6
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 twinpilot-realm.json /opt/keycloak/data/import/twinpilot-realm.json
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
