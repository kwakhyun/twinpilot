#!/usr/bin/env bash
# docker compose 스택(infra/docker-compose.yml)에 k6 부하 테스트를 실행한다. k6는 같은 compose 네트워크 안에서 돈다.
#   loadtest/run-local.sh api-mixed.js                       # 용량(twin-api 직접): 초당 10→30→60건, 약 5분
#   STAGES="20s:60,40s:60,20s:120,40s:120,20s:200,40s:200,10s:0" JOB_DURATION=3m loadtest/run-local.sh api-mixed.js
#   loadtest/run-local.sh edge-limits.js                     # nginx 경유 한도·권한 경계(약 50초)
# 결과 요약(JSON)은 loadtest/results/<스크립트>-<시각>.json에 남는다.
set -euo pipefail
script="${1:-api-mixed.js}"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$here/results"
chmod 777 "$here/results" 2>/dev/null || true
case "$script" in
  edge-limits.js) api_base="http://frontend:8080" ;;
  *)              api_base="${API_BASE:-http://twin-api:8080}" ;;
esac
docker run --rm --network "${COMPOSE_NETWORK:-twinpilot_default}" \
  -v "$here:/scripts:ro" -v "$here/results:/results" \
  -e API_BASE="$api_base" -e PUBLIC_BASE="${PUBLIC_BASE:-http://localhost:8088}" -e AUTH_BASE=http://frontend:8080 \
  -e DEMO_PASSWORD="${DEMO_PASSWORD:-twinpilot-demo}" \
  ${STAGES:+-e STAGES="$STAGES"} ${JOB_RATE:+-e JOB_RATE="$JOB_RATE"} ${JOB_DURATION:+-e JOB_DURATION="$JOB_DURATION"} \
  grafana/k6:2.3.0 run --summary-export "/results/${script%.js}-$(date +%Y%m%d-%H%M%S).json" "/scripts/$script"
