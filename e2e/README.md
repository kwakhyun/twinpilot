# E2E (Playwright)

전체 스택을 띄운 뒤 실행한다. CI의 `e2e` 잡도 같은 절차를 따른다.

```bash
docker compose -f infra/docker-compose.yml up -d --build --wait
cd e2e && npm ci && npx playwright install chromium
BASE=http://localhost:8088 node suite.mjs
```

| 변수 | 기본값 | 설명 |
|---|---|---|
| `BASE` | `http://localhost:8088` | 프론트엔드 주소 |
| `DEMO_PASSWORD` | `twinpilot-demo` | 데모 계정(viewer·analyst·planner·admin) 비밀번호 |
| `OUT` | `./screenshots` | 스크린샷·결과(`e2e-result.json`, `axe-*.json`) 저장 경로 |
| `CHROMIUM_PATH` | (Playwright 기본) | 미리 설치된 Chromium 경로 |

`suite.mjs`는 역할별로 Keycloak 로그인부터 시작해 지도·커버리지·건물 상세·분석 Job·코파일럿·모델·관리 화면을
확인하고, 시나리오마다 브라우저 콘솔 오류와 CSP 위반이 없는지, axe 접근성 위반(심각·중대)이 없는지 검사한다.

`oidc_login.py`는 브라우저 없이 Keycloak에 로그인해 액세스 토큰을 받는 보조 도구다(API 수동 점검·부하 테스트용).

```bash
python3 oidc_login.py --user analyst --password twinpilot-demo   # 표준 출력으로 액세스 토큰
```
