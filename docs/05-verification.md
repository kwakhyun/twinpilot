# 05. 검증 결과 (v0.2)

- 일시: 2026-10-09
- 환경: 클라우드 리눅스 컨테이너 1대(4 vCPU, 15GB), Docker 29.8.2. 모든 서비스(PostGIS·Keycloak·twin-api·mcp-server·ai-agent·
  frontend·관측성 스택)와 부하 생성기(k6)가 같은 호스트에서 돈다. 아래 성능 수치는 이 조건의 측정값이며 운영 클러스터의 용량이 아니다.
- 구성: PostgreSQL 16.15 + PostGIS 3.5.7(Alpine, `infra/postgis`), Keycloak 26.8.0, Java 21, Python 3.13, Node 22
- 데이터: 합성 도시(행정동 4, 도로 23 → 도로망 노드 143·간선 243, 건물 2,309, 소화전 250, AED 201, 소방서 2, 센서 111)
- 에이전트: 규칙 기반 플래너(API 키 없음). LLM 모드는 §7 참고

## 1. 자동 테스트

| 대상 | 종류 | 결과 | 주요 내용 |
|---|---|---|---|
| backend | 통합(Testcontainers PostGIS 16-3.5-alpine + 테스트용 OIDC 발급자) + 단위 | 41/41 | 역할별 URL 권한(기본 거부·401·403), Job 큐(SKIP LOCKED·리스·재시도·멱등 키·사용자 한도 429·취소·소유자 외 404), SSE, 센서 수집·파티션, 도로망·도달권, GeoJSON 가져오기(검증·좌표 변환·중복·REPLACE), 감사 로그(변경과 원자적 기록), 분석 캐시(동시 요청 합치기·TTL·상한·NOTIFY 무효화), glTF 검증 |
| frontend | 단위(Vitest) + `tsc --noEmit` | 13/13 | SSE 파서, 지도 액션 검증, 색상 구간, point-in-polygon |
| mcp-server | 메모리 내 MCP 세션 | 12 통과·1 건너뜀 | 도구 9종·리소스·프롬프트, 토큰 검증·교환, 스키마 위반 거부, 진행률 알림(건너뜀 1건은 실서버 연동, `TWIN_API_URL` 지정 시 실행) |
| ai-agent | 그래프·SSE·플래너·평가 하네스 | 43 통과·1 건너뜀 | 도구 선택 회귀 18문항, LLM 경로 이벤트, LLM 장애 시 규칙 기반 전환, 토큰 교환·사용자별 한도, 평가 채점 로직(건너뜀 1건은 PostgreSQL 체크포인터, `TEST_CHECKPOINT_DB_URL` 지정 시 실행) |
| E2E | Playwright(헤드리스 Chromium) + axe | 12/12 | 아래 §2 |
| 평가 하네스 | 실제 스택 `/agent/chat` | 12/12(규칙 기반) | 아래 §7 |
| 매니페스트 | kubeconform·kube-apiserver dry-run·promtool | 통과 | 아래 §8 |

## 2. E2E (docker compose 전체 스택, nginx 경유, 역할별 로그인)

| # | 역할 | 시나리오 |
|---|---|---|
| 1 | viewer | Keycloak 로그인 → 역할 표시, 관리 탭 없음 |
| 2 | viewer | 커버리지 분석·건물 상세(Three.js 단면) |
| 3 | viewer | 도로망 출동 시간 분석·도달권 표시 |
| 4 | viewer | 최적입지 추천 버튼 비활성(권한 없음) |
| 5 | viewer | 접근성(axe) 위반 0건 |
| 6 | viewer | 코파일럿 출동 시간 질문 → 도구 호출·지도 액션 |
| 7 | viewer | 새로고침 후 대화 복원(agent DB 체크포인트) |
| 8 | analyst | 추천 Job(SSE 진행률) 실행, 계획 배치는 비활성 |
| 9 | planner | 추천 → 계획 배치 → 재분석(커버리지 44.4% → 100%, 한빛동 AED) |
| 10 | planner | 3D 모델 업로드 → 검증 Job → 미리보기 |
| 11 | admin | GeoJSON 검증(dry run) → 적재 → 감사 로그 |
| 12 | admin | 로그아웃 → 로그인 화면 |

브라우저 콘솔 오류 0건, CSP 위반 0건, axe 위반 0건(모든 심각도).

시연 영상 녹화 중 찾아 고친 결함: docker compose에서 Keycloak 컨테이너를 중지 후 다시 시작하면 tmpfs()가 root 소유(755)로 바뀌어 테마 정적 파일의 gzip 캐시를 만들지 못하고, 브라우저(압축 요청)에 404를 돌려 로그인 화면에 스타일이 빠짐 → tmpfs에 소유자(uid 1000)·권한을 지정. 새로 만든 컨테이너에서는 재현되지 않아 E2E가 놓쳤다(Kubernetes는 emptyDir라 해당 없음).

E2E로 찾아 고친 결함(v0.2): Cesium Viewer의 Knockout `eval`이 CSP에 막힘 → CesiumWidget으로 전환, nginx가 포트를 뺀 Host를 넘겨
Keycloak 토큰 엔드포인트 주소가 틀어짐 → `$http_host`, 대화 기록 API가 에이전트 서비스 계정 비활성으로 500 → 서비스 계정 활성화,
주 버튼 명도 대비 3.63:1 → 4.5:1 이상, 측면 패널 제목 단계 건너뜀(h1 → h3) → h2.

## 3. 인증·권한·토큰 흐름

| 확인 | 결과 |
|---|---|
| 익명 API 호출 | 401 ProblemDetail |
| VIEWER의 Job 생성 / ANALYST의 감사 로그 조회 | 403 |
| 다른 사용자의 Job·진행 이벤트 조회 | 404(존재 여부 비공개). 문서 작성 중 검토에서 발견해 수정 |
| 코파일럿: VIEWER가 추천 요청 | 에이전트 → MCP → twin-api 토큰 교환 후 twin-api가 403, 에이전트가 권한 없음 안내 |
| 토큰 audience | 웹 토큰은 twinpilot-api·twinpilot-agent, 교환 토큰은 twinpilot-mcp → twinpilot-api. 다른 audience 토큰은 거부 |
| 서비스 계정 시크릿 교체 | Keycloak 값만 바꾸면 기존 서비스의 도구 호출 실패 → Secret 갱신·재시작 후 정상. Keycloak 재생성 후에도 값 유지(realm 가져오기 건너뜀) |
| refresh token 회전 | 같은 SSO 세션의 두 탭이 각자 갱신해도 정상. 같은 refresh token을 두 번 쓰면 세션이 끊김(재사용 감지, 의도한 동작) |
| 무차별 대입 방어 | 같은 계정으로 수십 번 동시 로그인 시 잠시 잠김(의도한 동작). 부하 테스트는 VU마다 SSO로 토큰을 받도록 설계 |

## 4. 다중 레플리카·장애 대응(twin-api 2개)

| 시나리오 | 결과 |
|---|---|
| 레플리카 1에 Job 제출, 레플리카 2에서 SSE 구독 | progress ×3 → done 수신(LISTEN/NOTIFY 팬아웃) |
| 두 레플리카의 센서 상태 | 최신 측정 시각 차이 0초, 센서 111개 동일(시뮬레이터는 리더 하나만 실행) |
| 15만 피처 가져오기(검증) 중 실행 노드 `kill -9` | 남은 노드가 리스 만료 후 이어받아 74초 뒤 SUCCEEDED(시도 2회), 15만 건 검증 7.2초, 감사 기록(JOB_SUBMIT) 1건 |
| 쿼리 시간 초과 | 재시도하지 않고 바로 실패 처리(같은 입력은 다시 해도 실패) |
| 비정상 종료로 남은 스테이징 | 매시간 정리(6시간 경과분) |
| LLM API 장애 | 규칙 기반 플래너로 이어서 답변, `notice` 이벤트, 대화 기록 유지(단위·그래프 테스트) |
| Keycloak 2노드 | jdbc-ping으로 클러스터 형성(view 2), 읽기 전용 루트에서 기동 5초 |
| Keycloak 26.6.4 → 26.8.0 | 기존 DB에서 모델 마이그레이션(26.7.0 → 26.8.0) 후 정상, 로그인·토큰 교환·E2E 통과 |

## 5. 부하 테스트(k6, `loadtest/`)

### 5.1 혼합 부하(twin-api 직접, 화면 사용 비율: 상세 30%·검색 15%·통계·센서·시설·커버리지 각 10%·전체 레이어·도로·출동 시간 각 5%) + 분석 Job 분당 6건

| 조건 | 요청 | 오류 | 전체 p95 | 엔드포인트 p95 | PostGIS CPU 최대 |
|---|---|---|---|---|---|
| 단계 10→30→60 req/s, 5분(통계 캐시만 적용) | 8,890 | 0% | 61ms | 상세 6.5ms, 검색 6.4ms | 152% |
| 단계 60→120→200 req/s, 3분 10초, **캐시 도입 전** | 22,072 | 0% | 776ms | 커버리지 1.23s, 출동 시간 2.01s, 상세 747ms | 317% |
| 같은 단계, **분석 캐시 적용(최종 구성)** | 21,927 | 0% | 20ms | 커버리지 3.6ms, 출동 시간 3.3ms, 통계 2.8ms, 전체 건물 GeoJSON 45ms, 상세 5.8ms | 72% |
| 같은 단계, 분석 조건을 매번 바꿈(캐시 미적중) | 14,132 | 0.9%(모두 503) | 4.5s | 커버리지 8.6s | 363% |
| 60 req/s 1분, 캐시 미적중 | 4,000 | 0% | 143ms | 커버리지 1.95s, 상세 10ms | — |
| 120 req/s 1분, 캐시 미적중 | 5,872 | 1.4%(모두 503) | 5s | — | 포화 |

- 분석 Job: 전 구간에서 제출 → 완료 p95 1.0초(캐시 미적중 포화 구간 제외).
- 해석: 이 단일 호스트에서는 PostGIS CPU가 병목이다. 같은 조건의 분석이 반복되는 화면 사용에서는 캐시로 초당 200건을 오류 없이
  처리했고, 조건이 매번 다르면 초당 60건 부근이 한계였다. 포화 구간에서도 500 없이 503 + `Retry-After`로만 거절했다.
- 캐시 미적중 단일 요청(부하 없음): 커버리지(전체·사각지대 포함) 53~79ms, 출동 시간 12~16ms, 통계 52~70ms, 건물 레이어 56~64ms(119KB gzip).

### 5.2 부하 테스트로 찾아 고친 결함

| 결함 | 원인 | 조치 | 결과 |
|---|---|---|---|
| 통계 API가 초당 30~40건에서 중앙값 3.3초, 500 오류 19건 | 요청마다 8개 질의를 병렬 실행해 커넥션 풀(20) 고갈, 5초 대기 후 500 | 동시 요청 합치기 + 15초 캐시 + 데이터 변경 시 NOTIFY 무효화, 풀 고갈은 503 + Retry-After | p95 7ms, 5xx 0건 |
| 초당 200건에서 커버리지·출동 시간 p95 1~2초 | 같은 조건의 공간 분석을 매번 다시 계산 | 같은 캐시 구조를 분석 API에 적용(조건별, 항목 수 상한) | p95 3~4ms, PostGIS CPU 317% → 72% |
| 포화 시 감사 기록 누락 1건 | 감사 기록을 본 작업과 다른 커넥션·트랜잭션으로 씀 | 변경과 감사 기록을 한 트랜잭션으로(함께 롤백). 묶을 수 없는 곳은 내용 전체를 로그로 보존 + 실패 수 경보 | 통합 테스트로 확인 |
| 부하 스크립트의 토큰 갱신 실패 | VU들이 refresh token 하나를 나눠 씀(realm의 재사용 감지) | VU마다 SSO 쿠키로 자기 토큰 체인 | 5분 실행 중 갱신 포함 오류 0 |

### 5.3 경계 보호(nginx 경유, `edge-limits.js`)

| 항목 | 결과 |
|---|---|
| 한 IP에서 초당 80건(20초) | 669건 처리, 941건 429(IP 한도 30r/s·burst 60), 5xx 0 |
| 한 사용자가 초당 1건 질문(15초) | 6건 429(사용자 분당 20·burst 5, IP 2r/s), 처리된 답변 p95 117ms(규칙 기반) |
| 익명 401, VIEWER Job 403, ANALYST 감사 로그 403 | 모두 기대대로 |

## 6. 백업·복구

docker compose 스택에서 Kubernetes 매니페스트와 같은 스크립트·이미지·사용자(uid 70, 읽기 전용 루트)로 확인했다.

| 단계 | 결과 |
|---|---|
| 덤프 | twinpilot 469KB, keycloak 261KB, agent 77KB(압축), 목록 읽기로 손상 확인, SHA256SUMS |
| 업로드 | S3 호환 저장소(adobe/s3mock)에 5개 파일, 파일 수 검증, 보관 기간 지난 접두어 삭제 |
| 복구 | 빈 PostGIS에 부트스트랩 SQL → 다운로드·체크섬 확인 → `pg_restore --clean --create` |
| 대조 | 건물 2,309·시설 454·도로 간선 243·Flyway 8 일치(이전 이미지에서는 노드 143·센서 111·측정값 48,840건·파티션 5개도 일치), 새 비밀번호로 keycloak 사용자 접속 |

## 7. 코파일럿 평가(`ai-agent/evals`)

| 모드 | 결과 |
|---|---|
| 규칙 기반(현재 환경) | 12/12 통과, LLM 전용 3건(대화 맥락·추측 금지·프롬프트 주입) 건너뜀, 응답 p50 0.11초·p95 0.38초 |
| 처음 실행 | 10/12 — 규칙 플래너 결함 2건 발견: "건물 5곳"의 5를 건물 ID로 해석, "AED 접근성 현황 … 사각지대 고위험 건물 수"를 커버리지 대신 건물 검색으로 처리 → 수정·회귀 테스트 추가 |
| LLM(claude-opus-5-5) | 미실행(API 키 필요). 키를 넣고 같은 명령으로 15건 전체를 채점한다 |

근거성 채점: 답변에 들어간 수치를 twin-api가 직접 계산한 값(커버리지율, 4분 초과 건물 563동, 전체 건물 수 등)과 대조한다.

## 8. 매니페스트·이미지·공급망

| 항목 | 결과 |
|---|---|
| kubeconform(Kubernetes 1.34, strict) | local·ncp 오버레이 + 복구 Job: 64 유효, CRD 리소스 5개는 아래 dry-run으로 확인 |
| kube-apiserver 1.34.1 서버 측 dry-run | PSA `restricted` 강제 네임스페이스에서 local 27·ncp 41 리소스 통과(경고 0), Argo CD 4(CRD 설치 후) |
| promtool | 경보 15개(compose 10 + Kubernetes 5) 문법 통과, `generate.py --check`로 compose·Kubernetes 경보 동기화 확인 |
| 도메인 일괄 치환 | `PUBLIC_HOST` 한 값으로 Ingress host와 공개 URL 6개가 함께 바뀜 |
| Trivy(수정 가능한 HIGH·CRITICAL, DB 2026-10-09) | twin-api 0, mcp-server 0, ai-agent 0, keycloak 0(26.6.4는 37), aws-cli 2.37.12 0(2.37.4는 14), postgis 17·frontend 1 — 모두 Alpine 패키지로 CI의 `apk upgrade`가 해소(이 환경은 Alpine 저장소 차단). 공개 postgis/postgis:16-3.5는 103(gosu Go 표준 라이브러리 58 포함) → gosu 제거·Alpine 기반으로 교체 |
| pip-audit | mcp-server·ai-agent 해시 고정 lock 모두 알려진 취약점 없음 |
| npm audit | frontend·e2e 0 |
| 이미지 크기 | twin-api 591MB, keycloak 757MB, postgis 724MB, ai-agent 404MB, mcp-server 270MB, frontend 108MB |
| 실행 제한 | 모든 컨테이너 비루트·읽기 전용 루트·권한 상승 금지(Keycloak·PostGIS 포함) |

## 9. 관측성(이전 측정, 같은 구성)

- 코파일럿 질의 1건 = 트레이스 1개(46 span, ai-agent → mcp-server → twin-api → DB)
- Prometheus 대상 5개 UP, Grafana 대시보드 14 패널, 로그는 모두 JSON(trace ID 포함)

## 10. 검증하지 못한 항목

| 항목 | 이유 | 대신 한 것 |
|---|---|---|
| LLM 모드 응답 품질 | API 키 없음 | 평가 하네스 준비(15건, 근거성·안전성 채점), 장애 시 전환 경로 테스트 |
| NCP 실배포(NKS·ALB·NCR·Object Storage·Certificate Manager) | NCP 계정 없음 | 매니페스트 스키마·서버 dry-run, 백업은 S3 호환 저장소로 확인 |
| `terraform validate`·`plan` | 작업 환경에서 Terraform 레지스트리 차단 | `terraform fmt` |
| 실제 클러스터에서 파드 스케줄링·NetworkPolicy·HPA 동작 | 샌드박스에서 kind 기동 불가 | API 서버 dry-run, compose로 서비스 간 흐름 확인 |
| Alpine 패키지 업그레이드 후 이미지 | Alpine 저장소 차단 | 남은 항목이 모두 수정 버전 있는 패키지임을 확인, CI 기본값 `APK_UPGRADE=true` |
| 실제 공공데이터 적재 | 데이터·이용 조건 미확정 | 같은 스키마의 가져오기 파이프라인과 15만 피처 시험 |
| 운영 규모 부하 | 단일 호스트 | 병목(PostGIS CPU)과 캐시 효과를 측정, 운영 클러스터에서 같은 스크립트로 재측정 필요 |
