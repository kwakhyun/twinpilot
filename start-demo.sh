#!/usr/bin/env bash
# TwinPilot 로컬 데모 실행(macOS·Linux). macOS는 start-demo.command를 더블클릭해도 된다.
#
#   ./start-demo.sh          # 준비 확인 → 빌드·실행 → 브라우저 열기
#   ./start-demo.sh stop     # 중지(데이터 유지)
#   ./start-demo.sh reset    # 중지 + 데이터 삭제(처음 상태로)
#   ./start-demo.sh logs     # 최근 로그
set -euo pipefail
cd "$(dirname "$0")"
COMPOSE=(docker compose -f infra/docker-compose.yml)
URL=http://localhost:8088
OS=$(uname -s)

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

case "${1:-start}" in
  stop)  "${COMPOSE[@]}" stop; exit 0 ;;
  reset) "${COMPOSE[@]}" down -v; exit 0 ;;
  logs)  "${COMPOSE[@]}" logs --tail 80; exit 0 ;;
  start) ;;
  *) die "사용: $0 [start|stop|reset|logs]" ;;
esac

say "1/4 Docker 확인"
if ! command -v docker >/dev/null 2>&1; then
  if [ "$OS" = Darwin ]; then
    if command -v brew >/dev/null 2>&1; then
      brew install --cask docker
      echo "Docker Desktop을 설치했습니다. 응용 프로그램에서 Docker를 한 번 열어 약관에 동의한 뒤 이 스크립트를 다시 실행하세요."
      open -a Docker || true
      exit 0
    fi
    die "Docker Desktop이 없습니다. https://www.docker.com/products/docker-desktop/ 에서 설치한 뒤 다시 실행하세요."
  else
    echo "Docker를 설치합니다(sudo 비밀번호를 물을 수 있습니다)."
    curl -fsSL https://get.docker.com | sudo sh
    sudo usermod -aG docker "$USER" || true
    die "Docker를 설치했습니다. 로그아웃 후 다시 로그인(또는 재부팅)한 뒤 이 스크립트를 다시 실행하세요."
  fi
fi
if ! docker info >/dev/null 2>&1; then
  if [ "$OS" = Darwin ]; then
    echo "Docker Desktop을 시작합니다…"
    open -a Docker || die "Docker Desktop을 열 수 없습니다. 직접 실행한 뒤 다시 시도하세요."
  else
    sudo systemctl start docker 2>/dev/null || true
  fi
  for _ in $(seq 1 90); do docker info >/dev/null 2>&1 && break; sleep 2; done
  docker info >/dev/null 2>&1 || die "Docker가 응답하지 않습니다. Docker Desktop이 완전히 켜졌는지(고래 아이콘) 확인한 뒤 다시 실행하세요."
fi
docker compose version >/dev/null 2>&1 || die "docker compose(v2)가 필요합니다. Docker Desktop을 최신으로 업데이트하세요."
mem_gb=$(( $(docker info --format '{{.MemTotal}}') / 1024 / 1024 / 1024 ))
echo "Docker 사용 가능 메모리: ${mem_gb}GB"
if [ "$mem_gb" -lt 5 ]; then
  echo "※ 6GB 이상을 권장합니다. Docker Desktop → Settings → Resources → Memory에서 늘리면 더 안정적입니다."
fi

say "2/4 포트 확인(8088·8180·5432)"
for p in 8088 8180 5432; do
  # 리스닝 프로그램이 없으면 lsof·ss가 1을 돌려준다. || true가 없으면 set -e 때문에 조용히 끝난다.
  users=""
  if command -v lsof >/dev/null 2>&1; then
    users=$(lsof -nP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $1}' | sort -u | tr '\n' ' ' || true)
  elif command -v ss >/dev/null 2>&1; then
    users=$(ss -ltnpH "sport = :$p" 2>/dev/null | head -1 || true)
  fi
  # 이미 떠 있는 TwinPilot 컨테이너(Docker가 잡은 포트)는 문제 없다.
  if [ -n "$users" ] && ! docker ps --format '{{.Names}} {{.Ports}}' | grep -q "twinpilot.*:$p->"; then
    die "포트 $p 를 다른 프로그램($users)이 쓰고 있습니다. 그 프로그램을 끈 뒤 다시 실행하세요."
  fi
done
echo "사용 가능"

say "3/4 빌드·실행(처음에는 10~20분, 이후 1분 안팎)"
"${COMPOSE[@]}" up -d --build --wait --wait-timeout 900 \
  || { "${COMPOSE[@]}" ps; die "실행에 실패했습니다. './start-demo.sh logs' 결과를 보내 주세요."; }

say "4/4 완료"
echo "주소    : $URL"
echo "계정    : viewer / analyst / planner / admin"
echo "비밀번호: twinpilot-demo"
echo "중지    : ./start-demo.sh stop   (처음 상태로: ./start-demo.sh reset)"
if [ "$OS" = Darwin ]; then open "$URL"; else xdg-open "$URL" >/dev/null 2>&1 || true; fi
