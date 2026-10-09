# 05. 검증 결과

- 일시: 2026-10-09
- 환경: 클라우드 리눅스 컨테이너(4 vCPU, 15GB), Docker 29.8, PostgreSQL 16 + PostGIS 3.5.2(GEOS 3.9), Java 21, Node 22, Python 3.13
- 데이터: 합성 도시(행정동 4, 도로 23, 건물 2,309, 소화전 250, AED 201, 소방서 2, 센서 111)

## 1. 자동 테스트

| 대상 | 종류 | 수 | 내용 |
|---|---|---|---|
| backend | 통합(Testcontainers PostGIS) + 단위 | 15 | GeoJSON·ETag, 검색 필터·정렬, 상세 병렬 조회, 커버리지와 검색의 미커버 건수 일치, ProblemDetail, 추천 Job SSE·커버리지 개선·계획 시설 반영, 센서 SSE, 통계, 모델 비동기 처리(정상·실패), Actuator 프로브·Prometheus, glTF 검증 5종 |
| frontend | 단위(Vitest) + `tsc --noEmit` | 9 | SSE 파서(청크 경계), 색상 구간, point-in-polygon(구멍 포함), 지도 액션 검증(잘못된 액션 무시) |
| mcp-server | 메모리 내 MCP 세션 + 실서버 연동 | 7 | 도구·리소스·프롬프트 목록, Context 파라미터 비노출, 행정동 이름 해석, LLM 텍스트와 지도 액션 분리, 스키마 위반 거부, 비동기 Job 진행률 알림, 실제 twin-api 연동 |
| ai-agent | 그래프·SSE·플래너 | 26 | 도구 선택 평가셋 13문항(정확도 100%), LLM 경로 이벤트 순서, 시스템 프롬프트 캐시 지점, 세션 append-only, 도구 오류 처리, 호출 한도 후 기록 유효성, PostgreSQL 체크포인터로 인스턴스 간 세션 공유 |

LLM 경로는 스크립트 응답을 내는 가짜 채팅 모델로 검증했다. `ChatAnthropic`이 만드는 실제 요청 페이로드도 확인했다: `model: claude-opus-5-5`, `output_config.effort: medium`, `thinking: adaptive`, `betas: [server-side-fallback-2026-07-01]`, `fallbacks: "default"`, 시스템 프롬프트 `cache_control`, `tool_choice` 미지정(auto).

## 2. E2E (Playwright, 헤드리스 Chromium + SwiftShader WebGL)

컨테이너 스택(docker compose, nginx 경유)과 개발 프리뷰(Vite) 양쪽에서 통과했다. 브라우저 콘솔 오류는 0건이다.

| # | 시나리오 | 확인 내용 |
|---|---|---|
| 1 | 초기 화면 | Cesium 3D 도시, 범례, 센서 실시간 스트림 |
| 2 | 커버리지 분석 | 미커버 162동 하이라이트, 사각지대 폴리곤, 행정동별 표 |
| 3 | 건물 선택 | 위험 요인 계측, 출동 여건, Three.js 층별 단면 |
| 4 | 최적입지 추천 | Job SSE 진행률, 추천 3곳·반경 원, 커버리지 93% → 97.9% |
| 5 | 대시보드 | 스탯 타일, 막대 차트 툴팁, 표 |
| 6 | 코파일럿: "한빛동에 AED 3대를 추가한다면?" | `recommend_facility_sites` 호출, 추천 입지 표시, 119동 하이라이트 |
| 7 | 코파일럿 후속 질문(같은 세션) | `search_buildings`, 119동 하이라이트 |
| 8 | 코파일럿: "건물 696번 브리핑" | `get_building_detail`, 건물 선택·카메라 이동 |
| 9 | 3D 모델 | 샘플 GLB 업로드 → READY(정점 120, 삼각형 60) → Cesium 배치 → Three.js 미리보기 |

E2E로 발견해 수정한 결함
- Vite 프록시의 `changeOrigin` 때문에 POST가 CORS 403 → Host를 유지하고, 백엔드에 `forward-headers-strategy` 적용
- `vite-plugin-static-copy` v4가 원본 경로 구조를 보존해 Cesium 자산 경로가 어긋남 → `stripBase`
- Cesium 메타 패키지 caret 범위로 엔진 버전이 섞여 빌드 실패 → npm `overrides`로 고정
- 코파일럿 답변에 구조 코드(WOOD)가 노출됨 → 한글 표기

## 3. 성능 측정 (컨테이너 스택, nginx 경유, 5회 중앙값)

| 요청 | 결과 |
|---|---|
| GET 건물 레이어(2,309동 GeoJSON) | 84ms, 913KB → gzip 119KB, 재요청 시 304 |
| GET 건물 검색(사각지대·노후 조건) | 39ms |
| GET 건물 상세(병렬 4질의) | 10~36ms |
| GET 대시보드 집계(병렬 8질의) | 82~119ms |
| POST 커버리지(전체, 사각지대 폴리곤 포함) | 90ms |
| POST 커버리지(행정동 1곳) | 63ms |
| 추천 Job(소화전 3곳, 전체 영역) | 214~467ms (3회) |
| 추천 Job(AED 3곳, 한빛동) | 168~187ms (3회) |

최적화 전후(같은 데이터)

| 항목 | 전 | 후 | 조치 |
|---|---|---|---|
| 추천 Job(소화전, 전체) | 6.5~7.1초 | 0.21~0.47초 | 후보를 25m 격자 → 도로 중심선 분할점으로 변경(공간 인덱스를 타지 않던 건물 내부 검사 제거), CTE `MATERIALIZED` |
| 같은 질의(SQL 단독) | 889ms | 188ms | 커넥션 초기화 `SET jit = off` |
| 커버리지(전체) | 445ms | 90ms | 위와 같음(JIT 비용 제거) |
| 건물 레이어 전송량 | 913KB | 119KB | weak ETag로 Tomcat gzip 활성화 |

계획서의 목표(커버리지 p95 1초 이내, 최적입지 10초 이내)를 이 측정에서 충족했다. 부하 테스트(동시 사용자)는 하지 않았다.

## 4. 컨테이너·Kubernetes

| 항목 | 결과 |
|---|---|
| 이미지 빌드 | twin-api 565MB, ai-agent 372MB(PostgreSQL 체크포인터 포함), mcp-server 233MB, frontend 100MB(비루트 nginx) |
| docker compose | 5개 서비스 기동, twin-api readiness 헬스체크, nginx 경유 SSE(`text/event-stream`) |
| 제한 실행 | 4종 모두 `--read-only --cap-drop ALL --security-opt no-new-privileges` + 비루트로 정상 동작(API, 에이전트 대화, 모델 업로드·다운로드). emptyDir 쓰기 권한을 위해 `fsGroup`을 추가했다 |
| 스키마 검증 | kubeconform(Kubernetes 1.34, strict): local 21 + ncp 27 = 48/48 |
| API 서버 적용 | 단독 kube-apiserver 1.34.1 + etcd 3.6.5에 local 오버레이 21개 적용. Pod Security `restricted` 경고는 PostGIS만 발생(앱 4종 없음) |
| NCP 오버레이 | 서버 측 dry-run 27/27 |
| 시드 동시성 | PostgreSQL advisory lock으로 레플리카 동시 기동 시 한 번만 적재 |

## 5. 검증하지 못한 항목

| 항목 | 이유 |
|---|---|
| 실제 Claude API 호출(LLM 모드 응답 품질) | API 키 없음. 페이로드와 그래프 경로만 검증했다 |
| NCP 실배포(NKS·ALB·Object Storage·NCR) | NCP 계정 없음. 매니페스트는 스키마·API 서버 검증까지 했다 |
| `terraform validate` / `plan` | 작업 환경에서 Terraform 레지스트리 접근 차단. `terraform fmt`만 통과 |
| 파드 스케줄링·NetworkPolicy 실동작 | 샌드박스의 중첩 컨테이너 제한으로 kind 클러스터를 기동하지 못했다(runc 오류) |
| S3(Object Storage) 저장소 구현 | 컴파일만 확인했다. 실제 버킷 연동은 하지 않았다 |
| 실제 공공데이터 적재 | 네트워크 정책상 공공데이터포털 접근 불가. 합성 데이터로 대체했다 |
