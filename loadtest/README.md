# 부하 테스트(k6)

| 스크립트 | 대상 | 확인 내용 |
|---|---|---|
| `api-mixed.js` | twin-api 직접 | 화면 사용 패턴을 본뜬 조회·분석 요청(browse) + 비동기 분석 Job(jobs). 엔드포인트별 p95 기준 |
| `edge-limits.js` | frontend(nginx) 경유 | IP·사용자별 요청 한도 초과분이 5xx 없이 429로 거절되는지, 익명 401·권한 403 |
| `lib/auth.js` | Keycloak | 브라우저와 같은 Authorization Code + PKCE 로그인, VU(탭)별 토큰 체인과 갱신 |

```bash
docker compose -f infra/docker-compose.yml up -d --build --wait
loadtest/run-local.sh api-mixed.js       # 초당 10→30→60건, 약 5분
loadtest/run-local.sh edge-limits.js     # 약 50초
STAGES="20s:60,40s:60,20s:120,40s:120,20s:200,40s:200,10s:0" JOB_DURATION=3m loadtest/run-local.sh api-mixed.js
```

- k6는 compose 네트워크 안에서 돈다. 용량 측정은 nginx의 IP당 한도(30r/s)를 피하려고 twin-api를 직접 호출한다.
- 로그인은 사용자마다 setup에서 한 번만 비밀번호로 하고, VU는 SSO 쿠키로 각자 인가 코드를 받는다.
  같은 계정으로 수십 번 동시에 비밀번호 로그인하면 Keycloak 무차별 대입 방어가 계정을 잠시 잠근다.
  realm은 refresh token 회전을 켜 두었으므로, 같은 refresh token을 여러 VU가 나눠 쓰면 세션이 끊긴다.
- 운영 환경에서 실행할 때는 `PUBLIC_BASE`·`AUTH_BASE`·`API_BASE`를 공개 주소로 주고, 부하 테스트 전용 계정을 쓴다.

측정 결과와 환경은 [docs/05-verification.md](../docs/05-verification.md#부하-테스트)에 정리했다.
