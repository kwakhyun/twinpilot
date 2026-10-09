# 04. API (v0.2)

OpenAPI 문서는 twin-api의 `/api-docs`, Swagger UI는 `/swagger-ui.html`(운영 오버레이에서는 끔)에서 본다.
브라우저는 frontend(nginx)를 거쳐 같은 출처로 호출한다(`/api`, `/agent`, `/auth`).

## 1. 공통 규칙

| 항목 | 규칙 |
|---|---|
| 인증 | `Authorization: Bearer <access token>`. twin-api는 audience `twinpilot-api`, ai-agent는 `twinpilot-agent`, mcp-server는 `twinpilot-mcp`를 요구한다 |
| 권한 | 역할 위계 VIEWER < ANALYST < PLANNER < ADMIN, 센서 수집은 INGEST. 아래 표의 "역할"은 필요한 최소 역할 |
| 오류 | RFC 9457 ProblemDetail(`application/problem+json`): 400 검증 오류, 401 인증 필요, 403 권한 없음, 404 없음, 409 상태 충돌, 429 한도 초과, 503 일시 과부하 |
| 재시도 | 429·503은 `Retry-After`(초)를 준다. 클라이언트는 그 시간 뒤에 다시 시도한다 |
| 멱등성 | `POST /analysis/jobs`는 `Idempotency-Key` 헤더(사용자별)로 같은 요청의 재전송을 한 Job으로 묶는다 |
| 캐시 | GeoJSON 레이어는 weak ETag로 304를 돌려준다. 통계·커버리지·출동 시간 결과는 서버가 같은 조건에 재사용하고, 데이터가 바뀌면 즉시 무효화한다 |
| 요청 한도 | nginx IP당 `/api` 30r/s(burst 60), `/agent` 2r/s. 에이전트 사용자당 분당 20회(burst 5). 분석 Job 사용자당 동시 5개 |

## 2. twin-api REST (`/api/v1`)

### 2.1 조회·분석

| Method | Path | 역할 | 설명 |
|---|---|---|---|
| GET | `/me` | VIEWER | 사용자명·역할 |
| GET | `/regions`, `/roads`, `/bus-routes` | VIEWER | 행정동(기준 반경·건물 수), 도로(폭), 버스 노선. GeoJSON |
| GET | `/buildings?regionId&bbox&minRisk&minAge&useType&minFloors&uncoveredBy` | VIEWER | 3D 렌더링용 건물 GeoJSON(gzip·weak ETag) |
| GET | `/buildings/search?…&builtBefore&ids&sort=risk\|age\|floors\|height&limit` | VIEWER | 표·에이전트용 검색 `{total, avgRisk, maxRisk, items[]}` |
| GET | `/buildings/{id}` | VIEWER | 상세: 최근접 소화전·AED, 소방서 출동(도로망), 사다리차, 수압 센서 |
| GET | `/facilities?type&status` | VIEWER | 소화전·AED·소방서 GeoJSON |
| POST | `/analysis/coverage` | VIEWER | `{facilityType, radiusM?, regionId?, includePlanned?, includeBlindSpots?, topUncovered?}` → 요약·행정동별·미커버·사각지대·적용 기준 |
| POST | `/analysis/response` | VIEWER | `{regionId?, targetMin?(1~60), top?(0~200)}` → 도로망 출동 시간 요약(목표 내 비율·초과·p50/p90), 행정동별, 느린 건물 |
| GET | `/analysis/isochrones?minutes=3,5,7&stationId&bufferM=60` | VIEWER | 소방서 도달권 GeoJSON(최대 5개, 1~30분) |
| GET | `/analysis/network` | VIEWER | 도로망 그래프 상태(버전·노드·간선·생성 시각) |
| GET | `/stats/summary?oldAgeYears=30` | VIEWER | 대시보드 집계(1~200년) |
| GET | `/sensors?kind&alertsOnly` | VIEWER | 센서 최신값·경보 수 |
| GET | `/sensors/stream` | VIEWER | 센서 SSE(`readings`). 모든 레플리카가 같은 상태를 보낸다 |
| GET | `/sensors/{id}/history?limit&from` | VIEWER | 시계열 |
| GET | `/models`, `/models/{id}`, `/models/{id}/file` | VIEWER | 3D 모델 목록·메타·파일 |

### 2.2 변경·작업

| Method | Path | 역할 | 설명 |
|---|---|---|---|
| POST | `/analysis/jobs` | ANALYST | `{type: RECOMMEND_SITES\|REFRESH_RISK, facilityType, count, regionId, radiusM}` → 202 + `Location`. 동시 5개 초과 시 429 |
| GET | `/analysis/jobs?all&type&limit` | VIEWER | 내 Job 목록(`all=true`는 ADMIN) |
| GET | `/analysis/jobs/{id}` | VIEWER | 상태·진행률·결과. 만든 사람과 ADMIN만 볼 수 있고, 그 밖에는 404 |
| GET | `/analysis/jobs/{id}/events` | VIEWER | SSE: `progress`, `done`, `failed`, `cancelled`(조회 권한은 위와 같음) |
| DELETE | `/analysis/jobs/{id}` | ANALYST | 취소(만든 사람·ADMIN. 대기 중이면 즉시, 실행 중이면 다음 진행 단계에서) |
| POST | `/facilities` | PLANNER | 계획(PLANNED) 시설 `{type: HYDRANT\|AED, lon, lat, name?}` → 201 |
| DELETE | `/facilities/{id}`, `/facilities/planned` | PLANNER | 계획 시설 삭제(운영 중 시설은 409) |
| POST | `/models` (multipart `file`, `name`, `lon`, `lat`, …) | PLANNER | glTF/GLB 업로드 → 202, 검증 Job(MODEL_PROCESS) |
| PUT | `/models/{id}/placement` | PLANNER | `{lon, lat, heightM, headingDeg, scale}` |
| DELETE | `/models/{id}` | PLANNER | 메타·파일 삭제 |
| POST | `/sensors/readings` | INGEST | `{readings: [{sensorId \| sensorCode, ts, values{…}, lon?, lat?}]}`(최대 1,000건) → `{accepted, rejected, errors}` |
| POST | `/admin/imports` (multipart) | ADMIN | `file`, `layer`(BUILDING·HYDRANT·AED·FIRE_STATION·ROAD·REGION), `source`, `srid?`, `mode=UPSERT\|REPLACE`, `mapping?`(JSON), `dryRun`, `refreshRisk` → 202 + Job(IMPORT) |
| GET | `/admin/imports` | ADMIN | 가져오기 이력(검증 보고서 포함) |
| GET | `/admin/audit?action&actor&limit` | ADMIN | 감사 로그 |

예시

```bash
TOKEN=$(python3 e2e/oidc_login.py --user analyst --password twinpilot-demo)
curl -s -X POST localhost:8088/api/v1/analysis/coverage -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"facilityType":"HYDRANT","regionId":3}'
curl -s -X POST localhost:8088/api/v1/analysis/jobs -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"type":"RECOMMEND_SITES","facilityType":"HYDRANT","count":3}'
```

### 2.3 운영 엔드포인트(관리 포트 8081, Ingress 비노출)

`/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`, `/actuator/info`

## 3. MCP 서버 (`mcp-server`, Streamable HTTP `/mcp`, stdio)

OAuth 2.0 보호 리소스다. 토큰 audience `twinpilot-mcp`를 검증하고, twin-api 호출 때는 토큰 교환으로
`twinpilot-api` 토큰을 받아 사용자 권한으로 호출한다. 보호 리소스 메타데이터(RFC 9728, `/.well-known/oauth-protected-resource/mcp`)로 인가 서버를 알린다.

| 종류 | 이름 | 입력(주요) | 결과 / 지도 액션 |
|---|---|---|---|
| Tool | `list_regions` | — | 행정동·기준 반경 |
| Tool | `search_buildings` | `region`, `min_age`, `min_risk`, `use_types`, `min_floors`, `uncovered_by`, `sort`, `limit` | 요약 + 상위 N / `highlightBuildings` |
| Tool | `get_building_detail` | `building_id` | 위험 요인·출동 여건·센서 / `selectBuilding`, `flyTo` |
| Tool | `analyze_coverage` | `facility_type`, `region`, `radius_m`, `include_planned` | 커버리지·우선 대상 / `runCoverage`, `highlightBuildings` |
| Tool | `analyze_response_time` | `region`, `target_min` | 도로망 출동 시간·초과 건물 / `showIsochrones`, `highlightBuildings` |
| Tool | `recommend_facility_sites` | `facility_type`(HYDRANT·AED), `count`, `region`, `radius_m` | 전후 커버리지·추천 입지(진행률 알림, ANALYST 필요) / `showSites` |
| Tool | `get_dashboard_stats` | — | 전체·행정동별 통계 / `setColorMode` |
| Tool | `get_sensor_status` | `kind`, `alerts_only` | 경보 목록 / `showLayer`, `flyTo` |
| Tool | `list_3d_models` | — | 모델 목록 / `showLayer` |
| Resource | `twin://schema`, `twin://standards` | — | 데이터 사전, 분석 기준(근거 포함) |
| Prompt | `safety_briefing(region)` | — | 행정동 안전 브리핑 작성 지시 |
| HTTP | `/healthz`, `/metrics` | — | 프로브, Prometheus |

모든 도구는 `readOnlyHint=true`다. 결과는 `content`(LLM용 요약)와 `structuredContent{data, ui_actions}`로 나뉜다.

## 4. 에이전트 API (`ai-agent`)

`POST /agent/chat` `{message(1~2000자), sessionId?, context?: {selectedBuildingId}}` → `text/event-stream`

| 이벤트 | 데이터 |
|---|---|
| `tool_call` | `{id, name, args}` |
| `tool_result` | `{id, name, ok, summary}` |
| `ui_action` | 지도 액션(§5) |
| `token` | `{text}` — LLM 답변 스트리밍 조각 |
| `notice` | `{kind: "fallback", message}` — LLM 장애로 규칙 기반 응답으로 전환. 그때까지 받은 `token`은 버린다 |
| `message` | `{content, streamed}` — 최종 답변 |
| `done` | `{mode: llm\|rule, model, sessionId}` |
| `error` | `{message}` |

| Method | Path | 설명 |
|---|---|---|
| GET | `/agent/sessions/{sessionId}/messages` | 대화 기록 복원(본인 세션만) |
| GET | `/agent/health` | `{status, mode, model, toolsLoaded}` |
| GET | `/metrics` | Prometheus(nginx로 노출하지 않음) |

한도 초과는 429 ProblemDetail + `Retry-After`.

## 5. 지도 액션(ui_action) 스키마

프론트엔드(`frontend/src/copilot/actions.ts`)가 유형·좌표·ID 형식을 검증하고 모르는 액션은 무시한다.

| type | 필드 | 동작 |
|---|---|---|
| `highlightBuildings` | `ids[]`, `label` | 해당 건물만 강조, 카메라 맞춤 |
| `flyTo` | `lon`, `lat`, `height?` | 카메라 이동 |
| `selectBuilding` | `id` | 상세 패널 |
| `runCoverage` | `facilityType`, `regionId?`, `radiusM?` | 커버리지 재실행(사각지대·반경 표시) |
| `showSites` | `result` | 추천 입지·반경·라벨 |
| `showIsochrones` | `minutes[]`(1~5개) | 소방서 도달권 표시 |
| `setColorMode` | `mode: risk\|use\|age` | 건물 색상 기준 |
| `showLayer` | `layer`, `visible` | 레이어 토글 |
