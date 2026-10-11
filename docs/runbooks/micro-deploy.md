# 런북: 메모리 1GB 서버(NCP Micro) 한 대에 데모 배포

브라우저로 접속할 수 있는 시연용 배포다. 서버 한 대(vCPU 1, 메모리 1GB, 디스크 10GB)에 전체 서비스를 올린다.
운영 배포(다중 레플리카·백업·모니터링)는 [ncp-deploy.md](ncp-deploy.md)를 따른다.

## 운영 구성과의 차이

| 항목 | 운영(NKS) | 경량판(`infra/micro`) |
|---|---|---|
| 로그인 | Keycloak(메모리 약 720MB) | 데모용 경량 OIDC 발급자 `demo-idp`(약 30MB). 고정 계정 4개, 세션은 메모리에만 둔다 |
| 레플리카 | 서비스당 2개 이상, HPA | 1개 |
| HTTPS | ALB + Certificate Manager | Caddy가 Let's Encrypt 인증서를 자동 발급·갱신 |
| 관측성·백업 | Prometheus·Grafana, 일일 백업 | 없음(데모) |
| 처리 성능 | — | JVM C1 컴파일러만, DB 연결 30, 센서 측정 10초 주기·2일 보관, 업로드 20MB |
| 관리자 계정 | 있음 | 기본은 닫힘(`ENABLE_ADMIN=1`로 연다) |

`demo-idp`는 Keycloak과 같은 주소·토큰 형식을 쓰므로 다른 서비스 코드는 같고 설정만 다르다.
지원 범위: Authorization Code + PKCE, refresh token 회전·재사용 감지, client credentials, token exchange(에이전트 → MCP → twin-api),
로그아웃, 로그인 실패 잠금(계정별 10분에 5회, IP별 20회). 테스트: `infra/micro/demo-idp/test_idp.py`.

## 1. NCP에서 서버 만들기(콘솔)

메뉴 이름은 콘솔 개편에 따라 다를 수 있다. 막히면 [Server 생성 가이드](https://guide.ncloud-docs.com/docs/server-create-vpc)와
[ACG 가이드](https://guide.ncloud-docs.com/docs/server-acg-vpc)를 본다.

1. **결제 수단 등록**: Micro 서버의 무료 1년은 결제 수단을 등록한 달부터 센다. 1인 1대, 한국 거주자 조건이 있다.
2. **VPC·Subnet**: VPC를 하나 만들고, 그 안에 **Public** Subnet을 만든다.
3. **ACG**(방화벽): 새 ACG에 인바운드 규칙 3개를 넣는다.

   | 프로토콜 | 접근 소스 | 포트 | 용도 |
   |---|---|---|---|
   | TCP | 내 PC 공인 IP/32 | 22 | SSH |
   | TCP | 0.0.0.0/0 | 80 | 인증서 발급, HTTPS로 넘김 |
   | TCP | 0.0.0.0/0 | 443 | 서비스 |

4. **서버 생성**: 이미지 Ubuntu(22.04 또는 24.04), 서버 타입 **Micro**, 위의 Public Subnet과 ACG, 공인 IP 할당(월 약 4천 원대, 무료 아님).
   인증키(.pem)를 새로 만들어 내려받아 안전하게 보관한다.
5. **접속 정보**: 서버 목록에서 서버를 고르고 "관리자 비밀번호 확인"에 인증키를 넣어 root 비밀번호를 받는다.
   `ssh root@<공인 IP>`로 접속되는지 확인한다.

## 2. 배포

### A. PC에 Docker가 있을 때(macOS·Linux·WSL)

소스 루트에서 실행한다. PC에서 이미지를 빌드해 압축 스트림으로 서버에 바로 적재한다(서버 디스크에 묶음 파일을 남기지 않는다).

```bash
infra/micro/deploy.sh root@<공인 IP>
```

### B. PC에 Docker가 없을 때(미리 빌드한 이미지 묶음 사용)

`twinpilot-images-0.2.0-amd64.tar.gz`(x86_64, 약 520MB)를 받은 경우다.

```bash
# macOS·Linux·WSL
infra/micro/deploy.sh root@<공인 IP> --images twinpilot-images-0.2.0-amd64.tar.gz
```

```powershell
# Windows PowerShell(기본 내장 OpenSSH)
scp -r twinpilot\infra\micro root@<공인 IP>:/opt/twinpilot
scp twinpilot-images-0.2.0-amd64.tar.gz root@<공인 IP>:/opt/twinpilot/
ssh root@<공인 IP> "bash /opt/twinpilot/server-setup.sh all"
```

### 실행 결과

마지막에 접속 주소와 데모 비밀번호가 나온다.

```
주소     : https://1-2-3-4.sslip.io
데모 계정: viewer / analyst / planner, 비밀번호 (무작위 12자)
```

- 도메인이 없으면 공인 IP로 만든 `sslip.io` 주소를 쓴다(예: IP 1.2.3.4 → `1-2-3-4.sslip.io`). 이 주소로 Let's Encrypt 인증서가 발급된다.
- 내 도메인을 쓰려면 DNS A 레코드를 서버 IP로 만든 뒤 `PUBLIC_HOST=twin.example.com infra/micro/deploy.sh …`로 배포한다.
- 처음 만들 때만 쓰는 값: `PUBLIC_HOST`, `DEMO_USER_PASSWORD`, `ENABLE_ADMIN=1`. 이후에는 서버의 `/opt/twinpilot/.env`를 고친다.

## 3. 운영

| 작업 | 명령(서버에서) |
|---|---|
| 설정 변경(비밀번호·LLM 키·주소) | `/opt/twinpilot/.env` 수정 후 `sudo bash /opt/twinpilot/server-setup.sh up` |
| LLM 모드 켜기 | `.env`의 `ANTHROPIC_API_KEY=` 에 키를 넣고 위 명령 |
| 상태·메모리 | `cd /opt/twinpilot && docker compose ps && docker stats --no-stream` |
| 로그 | `docker compose logs --tail 100 twin-api` (서비스당 최대 20MB로 순환) |
| 중지·재시작 | `docker compose stop` / `docker compose start` (서버 재부팅 시 자동 시작) |
| 새 버전 | PC에서 `deploy.sh`를 다시 실행(`.env`·서명 키·DB는 유지) |
| 모두 지우기 | `docker compose down -v` (DB·인증서까지 삭제) |

- 주소(`PUBLIC_HOST`)를 바꾸면 이미 로그인한 사용자는 다시 로그인한다.
- `demo-idp`를 다시 시작하면 로그인 세션이 사라져 다시 로그인한다(서명 키는 `secrets/`에 남아 기존 토큰은 만료 전까지 유효).
- 무료 기간(1년)이 지나도 반납하지 않은 Micro 서버는 자동으로 유료 과금된다. 공인 IP는 처음부터 유료다.

## 4. 검증 결과(2026-10-11)

이 작업 환경에서 메모리 800MB·CPU 1개로 제한하고 **스왑 없이**(실제 서버보다 빡빡한 조건) 측정했다. 실제 Micro의 CPU 성능은 다를 수 있다.

| 항목 | 결과 |
|---|---|
| 기동 | 7개 서비스 35초 안에 정상(시드 데이터 적재 포함) |
| 메모리 | 대기 554MB, 역할별 E2E 중 최대 645MB, 초당 20건 부하 중 최대 662MB, 메모리 부족 0건 |
| E2E | 역할별 12개 시나리오 통과(로그인·코파일럿 토큰 교환·추천 Job·3D 업로드·데이터 적재·로그아웃), 콘솔·CSP 오류 0 |
| 부하(혼합, 분석 Job 분당 2건) | 초당 5·10·20건 모두 오류 0. 초당 20건에서 p95 54ms, 건물 상세 p95 10ms, 커버리지 p95 26ms |
| `demo-idp` 테스트 | 10개 통과(PKCE·코드 1회 사용, 외부 redirect 거부, refresh 재사용 감지, 잠금, 토큰 교환 대상 제한, 로그아웃) |
| 스크립트 | 빈 환경에서 `server-setup.sh up` 40초, `deploy.sh --images`(ssh 대신 로컬 실행으로 시험) 34초, `.env` 수정 후 재실행 반영 |

검증하지 못한 것: 실제 NCP 서버 생성·Let's Encrypt 발급(공인 IP 필요), `server-setup.sh prepare`의 Docker 설치·스왑 생성(이 환경은 systemd·스왑 불가),
실제 Micro CPU에서의 성능.
