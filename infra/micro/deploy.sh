#!/usr/bin/env bash
# PC에서 서버로 경량판을 배포한다(macOS·Linux·Windows WSL/Git Bash, ssh 필요).
#
#   infra/micro/deploy.sh root@<공인 IP>                          # 이 PC에서 이미지를 빌드해 보낸다(Docker 필요)
#   infra/micro/deploy.sh root@<공인 IP> --images 묶음.tar.gz      # 미리 빌드한 이미지 묶음을 보낸다(Docker 불필요)
#
# 서버는 메모리가 1GB라 이미지를 빌드하지 않는다. 이미지는 압축 스트림으로 바로 적재하므로 서버 디스크에 묶음을 남기지 않는다.
# PUBLIC_HOST·DEMO_USER_PASSWORD·ENABLE_ADMIN을 지정하면 서버의 첫 .env에 반영한다(server-setup.sh 참고).
set -euo pipefail
[ $# -ge 1 ] || { sed -n '2,8p' "$0"; exit 1; }
TARGET=$1; shift
IMAGES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --images) IMAGES=$2; shift 2 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
REMOTE=/opt/twinpilot
SSH_OPTS=(-o ServerAliveInterval=30)

echo "== 1/4 설정 파일 복사 → ${TARGET}:${REMOTE}"
ssh "${SSH_OPTS[@]}" "$TARGET" "mkdir -p $REMOTE"
tar -C "$ROOT/infra/micro" --exclude=.env --exclude=secrets --exclude='*.tar.gz' --exclude=__pycache__ \
    --exclude=test_idp.py -cf - . | ssh "${SSH_OPTS[@]}" "$TARGET" "tar -C $REMOTE -xf -"

echo "== 2/4 서버 준비(Docker·스왑)"
ssh "${SSH_OPTS[@]}" "$TARGET" "bash $REMOTE/server-setup.sh prepare"

echo "== 3/4 이미지 전송"
if [ -n "$IMAGES" ]; then
  ssh "${SSH_OPTS[@]}" "$TARGET" "gunzip | docker load" < "$IMAGES"
else
  # Apple Silicon 등 ARM PC에서도 서버(x86_64)용으로 빌드한다(에뮬레이션이라 느릴 수 있다).
  export DOCKER_DEFAULT_PLATFORM=linux/amd64
  docker compose -f "$ROOT/infra/docker-compose.yml" build postgis twin-api mcp-server ai-agent frontend
  docker pull caddy:2.10.2-alpine
  docker save twinpilot/postgis:0.2.0 twinpilot/twin-api:0.2.0 twinpilot/mcp-server:0.2.0 \
              twinpilot/ai-agent:0.2.0 twinpilot/frontend:0.2.0 caddy:2.10.2-alpine \
    | gzip -1 | ssh "${SSH_OPTS[@]}" "$TARGET" "gunzip | docker load"
fi

echo "== 4/4 실행"
ssh "${SSH_OPTS[@]}" "$TARGET" "PUBLIC_HOST='${PUBLIC_HOST:-}' DEMO_USER_PASSWORD='${DEMO_USER_PASSWORD:-}' \
  ENABLE_ADMIN='${ENABLE_ADMIN:-0}' bash $REMOTE/server-setup.sh up"
