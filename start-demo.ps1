# TwinPilot 로컬 데모 실행(Windows). start-demo.cmd를 더블클릭하면 이 스크립트가 실행된다.
#   start-demo.cmd          준비 확인 → 빌드·실행 → 브라우저 열기
#   start-demo.cmd stop     중지(데이터 유지)
#   start-demo.cmd reset    중지 + 데이터 삭제(처음 상태로)
#   start-demo.cmd logs     최근 로그
param([string]$Action = "start")
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot
$Compose = @("compose", "-f", "infra/docker-compose.yml")
$Url = "http://localhost:8088"

function Say($m) { Write-Host ""; Write-Host "== $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host ""; Write-Host $m -ForegroundColor Red; Read-Host "Enter를 누르면 닫힙니다"; exit 1 }
function DockerOk { docker info *> $null; return ($LASTEXITCODE -eq 0) }

switch ($Action) {
  "stop"  { docker @Compose stop; exit 0 }
  "reset" { docker @Compose down -v; exit 0 }
  "logs"  { docker @Compose logs --tail 80; Read-Host "Enter를 누르면 닫힙니다"; exit 0 }
  "start" { }
  default { Fail "사용: start-demo.cmd [start|stop|reset|logs]" }
}

Say "1/4 Docker 확인"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  if (Get-Command winget -ErrorAction SilentlyContinue) {
    Write-Host "Docker Desktop을 설치합니다(관리자 승인 창이 뜰 수 있습니다)."
    winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
    Fail "Docker Desktop을 설치했습니다. PC를 재부팅하고 Docker Desktop을 한 번 열어 약관에 동의한 뒤 start-demo.cmd를 다시 실행하세요."
  }
  Fail "Docker Desktop이 없습니다. https://www.docker.com/products/docker-desktop/ 에서 설치한 뒤 다시 실행하세요."
}
if (-not (DockerOk)) {
  $exe = Join-Path $Env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
  if (Test-Path $exe) { Write-Host "Docker Desktop을 시작합니다…"; Start-Process $exe }
  for ($i = 0; $i -lt 90 -and -not (DockerOk); $i++) { Start-Sleep -Seconds 2 }
  if (-not (DockerOk)) { Fail "Docker가 응답하지 않습니다. Docker Desktop이 완전히 켜졌는지 확인한 뒤 다시 실행하세요." }
}
docker compose version *> $null
if ($LASTEXITCODE -ne 0) { Fail "docker compose(v2)가 필요합니다. Docker Desktop을 최신으로 업데이트하세요." }
$memGb = [math]::Floor([double](docker info --format "{{.MemTotal}}") / 1GB)
Write-Host "Docker 사용 가능 메모리: ${memGb}GB"
if ($memGb -lt 5) { Write-Host "※ 6GB 이상을 권장합니다. Docker Desktop → Settings → Resources(또는 WSL 설정)에서 늘리면 더 안정적입니다." -ForegroundColor Yellow }

Say "2/4 포트 확인(8088·8180·5432)"
$ours = (docker ps --format "{{.Names}} {{.Ports}}") -join "`n"
foreach ($p in 8088, 8180, 5432) {
  $conn = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($conn -and ($ours -notmatch "twinpilot.*:$p->")) {
    $name = (Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue).ProcessName
    if ($name -notmatch "docker|wslrelay|com\.docker") { Fail "포트 $p 를 다른 프로그램($name)이 쓰고 있습니다. 그 프로그램을 끈 뒤 다시 실행하세요." }
  }
}
Write-Host "사용 가능"

Say "3/4 빌드·실행(처음에는 10~20분, 이후 1분 안팎)"
docker @Compose up -d --build --wait --wait-timeout 900
if ($LASTEXITCODE -ne 0) { docker @Compose ps; Fail "실행에 실패했습니다. 'start-demo.cmd logs' 결과를 보내 주세요." }

Say "4/4 완료"
Write-Host "주소    : $Url"
Write-Host "계정    : viewer / analyst / planner / admin"
Write-Host "비밀번호: twinpilot-demo"
Write-Host "중지    : start-demo.cmd stop   (처음 상태로: start-demo.cmd reset)"
Start-Process $Url
Read-Host "Enter를 누르면 이 창을 닫습니다(서비스는 계속 실행됩니다)"
