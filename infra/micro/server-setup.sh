#!/usr/bin/env bash
# TwinPilot 경량판 서버 설정(Ubuntu 22.04·24.04, root로 실행). 여러 번 실행해도 안전하다.
#
#   sudo bash server-setup.sh prepare   # Docker 설치, 스왑 만들기
#   sudo bash server-setup.sh up        # .env·서명 키 만들기(처음 한 번), 이미지 적재, 서비스 실행
#   sudo bash server-setup.sh all       # prepare + up (기본값)
#
# 환경 변수(처음 .env를 만들 때만 쓴다. 이후에는 .env를 직접 고친 뒤 `up`을 다시 실행한다)
#   PUBLIC_HOST         접속 주소. 비우면 공인 IP로 <a-b-c-d>.sslip.io를 만든다(도메인이 있으면 그 이름)
#   DEMO_USER_PASSWORD  viewer·analyst·planner 비밀번호. 비우면 무작위로 만든다
#   ENABLE_ADMIN=1      admin 계정(데이터 적재·감사 로그)을 연다. 공개 데모에서는 닫아 두기를 권한다
#   SWAP_SIZE_MB        스왑 크기(기본 2048)
set -euo pipefail
cd "$(dirname "$0")"
DIR=$(pwd)
STEP=${1:-all}

say() { printf '\n== %s\n' "$*"; }
[ "$(id -u)" -eq 0 ] || { echo "root로 실행하세요: sudo bash $0 $STEP" >&2; exit 1; }

prepare() {
  say "Docker"
  if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh
  fi
  systemctl enable --now docker >/dev/null
  docker compose version

  say "스왑(메모리 1GB 서버에서 순간 사용량을 받는다)"
  if swapon --show --noheadings | grep -q .; then
    swapon --show
  else
    local avail size
    avail=$(df --output=avail -BM / | tail -1 | tr -dc '0-9')
    size=${SWAP_SIZE_MB:-2048}
    # 이미지(약 2.3GB)와 DB가 들어갈 공간을 남긴다.
    if (( avail < size + 4096 )); then size=1024; fi
    fallocate -l "${size}M" /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count="$size" status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
    swapon /swapfile
    grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
    echo "스왑 ${size}MB 추가"
  fi
  sysctl -q -w vm.swappiness=10
  echo 'vm.swappiness=10' > /etc/sysctl.d/99-twinpilot.conf
}

rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-"${1:-32}"; }

init_env() {
  if [ ! -f .env ]; then
    say ".env 만들기"
    local host=${PUBLIC_HOST:-}
    if [ -z "$host" ]; then
      local ip
      ip=$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]')
      [[ $ip =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "공인 IP를 알 수 없습니다. PUBLIC_HOST를 지정하세요." >&2; exit 1; }
      host="${ip//./-}.sslip.io"
    fi
    local admin=""
    [ "${ENABLE_ADMIN:-0}" = "1" ] && admin=$(rand 16)
    umask 077
    cat > .env <<EOF
# TwinPilot 경량판 설정(server-setup.sh가 만들었다). 고친 뒤: sudo bash server-setup.sh up
PUBLIC_HOST=${host}
DEMO_USER_PASSWORD=${DEMO_USER_PASSWORD:-$(rand 12)}
ADMIN_PASSWORD=${admin}
LOGIN_HINT="데모 계정: viewer · analyst · planner"
# 넣으면 코파일럿이 LLM으로 답한다(비우면 규칙 기반)
ANTHROPIC_API_KEY=
DB_PASSWORD=$(rand)
AGENT_DB_PASSWORD=$(rand)
AGENT_CLIENT_SECRET=$(rand)
MCP_CLIENT_SECRET=$(rand)
SENSOR_GATEWAY_SECRET=$(rand)
EOF
    umask 022
  fi
  if [ ! -f secrets/idp-signing-key.pem ]; then
    say "로그인 서명 키 만들기"
    mkdir -p secrets
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/idp-signing-key.pem 2>/dev/null
    chown 10001:10001 secrets/idp-signing-key.pem    # demo-idp 컨테이너 사용자(uid 10001)만 읽는다
    chmod 600 secrets/idp-signing-key.pem
  fi
}

load_images() {
  local f
  for f in "$DIR"/twinpilot-images-*.tar.gz; do
    [ -e "$f" ] || continue
    say "이미지 적재: $(basename "$f")"
    gunzip -c "$f" | docker load
    rm -f "$f"          # 디스크 10GB: 적재 후 지운다
  done
  local missing=0 img
  for img in twinpilot/postgis:0.2.0 twinpilot/twin-api:0.2.0 twinpilot/mcp-server:0.2.0 twinpilot/ai-agent:0.2.0 \
             twinpilot/frontend:0.2.0; do
    docker image inspect "$img" >/dev/null 2>&1 || { echo "이미지 없음: $img" >&2; missing=1; }
  done
  [ "$missing" -eq 0 ] || { echo "이미지 묶음(twinpilot-images-*.tar.gz)을 $DIR 에 올린 뒤 다시 실행하세요." >&2; exit 1; }
}

up() {
  init_env
  load_images
  say "서비스 실행(vCPU 1개에서는 첫 실행에 몇 분 걸린다)"
  docker compose up -d --wait --wait-timeout 900
  docker image prune -f >/dev/null
  # shellcheck disable=SC1091
  set -a; . ./.env; set +a
  say "완료"
  echo "주소     : https://${PUBLIC_HOST}"
  echo "데모 계정: viewer / analyst / planner, 비밀번호 ${DEMO_USER_PASSWORD}"
  [ -n "${ADMIN_PASSWORD:-}" ] && echo "관리자   : admin, 비밀번호 ${ADMIN_PASSWORD}"
  echo "설정 파일: ${DIR}/.env (고친 뒤 sudo bash ${DIR}/server-setup.sh up)"
  echo "HTTPS 인증서는 처음 접속 전 자동 발급된다(80·443 포트가 열려 있어야 한다)."
  free -m | sed -n '1,3p'
  df -h / | tail -1
}

case "$STEP" in
  prepare) prepare ;;
  up) up ;;
  all) prepare; up ;;
  *) echo "사용: sudo bash $0 [prepare|up|all]" >&2; exit 1 ;;
esac
