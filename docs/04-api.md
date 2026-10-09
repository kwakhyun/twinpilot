# 04. API

OpenAPI 문서는 실행 중인 twin-api의 `/api-docs`, Swagger UI는 `/swagger-ui.html`에서 볼 수 있다(21개 경로).

## 1. twin-api REST (`/api/v1`)

| Method | Path | 설명 | 응답 |
|---|---|---|---|
| GET | `/regions` | 행정동 경계·용도지역·소화전 기준 반경·건물 수 | GeoJSON |
| GET | `/roads`, `/bus-routes` | 도로(폭), 버스 노선 | GeoJSON |
| GET | `/buildings?regionId&bbox&minRisk&minAge&useType&minFloors&uncoveredBy` | 3D 렌더링용 건물 | GeoJSON(gzip·weak ETag) |
| GET | `/buildings/search?…&sort=risk\|age\|floors\|height&limit` | 표·에이전트용 검색(대표점 좌표, 위험 요인) | `{total, avgRisk, maxRisk, items[]}` |
| GET | `/buildings/{id}` | 상세: 최근접 소화전·AED·소방서 도달, 사다리차, 수압 센서(병렬 조회) | `BuildingDetail` |
| GET | `/facilities?type&status` | 소화전·AED·소방서 | GeoJSON |
| POST | `/facilities` | 계획(PLANNED) 시설 배치 `{type: HYDRANT\|AED, lon, lat}` | 201 |
| DELETE | `/facilities/{id}`, `/facilities/planned` | 계획 시설 삭제(ACTIVE는 거부: 409) | 204 / `{deleted}` |
| POST | `/analysis/coverage` | `{facilityType, radiusM?, regionId?, includePlanned?, includeBlindSpots?, topUncovered?}` | 요약·행정동별·미커버 ID·사각지대 폴리곤·적용 기준 |
| POST | `/analysis/jobs` | `{type: RECOMMEND_SITES\|REFRESH_RISK, facilityType, count, regionId, radiusM, cellSizeM}` | 202 + `Location` |
| GET | `/analysis/jobs/{id}` | Job 상태·결과 | `JobView` |
| GET | `/analysis/jobs/{id}/events` | 진행률 SSE: `progress`, `done`, `failed` | text/event-stream |
| GET | `/stats/summary?oldAgeYears=30` | 대시보드 집계 | `Summary` |
| GET | `/sensors?kind&alertsOnly` | 센서 최신값·경보 수 | `{total, alerts, readings[]}` |
| GET | `/sensors/stream` | 센서 SSE(`readings`, 2초 주기) | text/event-stream |
| GET | `/sensors/{id}/history?limit` | 저장된 시계열 | `[{ts, value}]` |
| POST | `/models` (multipart `file`, `name`, `lon`, `lat`, …) | glTF/GLB 업로드 → 비동기 검증 | 202 + `ModelAsset(PROCESSING)` |
| GET | `/models`, `/models/{id}`, `/models/{id}/file` | 목록·메타(정점·삼각형·크기·경고)·파일 | — |
| PUT | `/models/{id}/placement` | `{lon, lat, heightM, headingDeg, scale}` | `ModelAsset` |
| DELETE | `/models/{id}` | 메타·파일 삭제 | 204 |

오류는 RFC 9457 `ProblemDetail`로 돌려준다(400 검증 오류, 404 없음, 409 상태 충돌).

예시

```bash
curl -X POST localhost:8080/api/v1/analysis/coverage -H 'Content-Type: application/json' \
  -d '{"facilityType":"HYDRANT","regionId":3}'
# → summary.coverageRate 89.7, uncoveredHighRisk 64, standard "소방기본법 시행규칙 [별표 3]: …"
```

## 2. MCP 서버 (`mcp-server`, Streamable HTTP `/mcp`, stdio)

| 종류 | 이름 | 입력(주요) | 결과 |
|---|---|---|---|
| Tool | `list_regions` | — | 행정동·기준 반경 |
| Tool | `search_buildings` | `region`(이름/ID), `min_age`, `min_risk`, `use_types`, `min_floors`, `uncovered_by`, `sort`, `limit` | 요약 + 상위 N개 / ui: `highlightBuildings`(전체 ID) |
| Tool | `get_building_detail` | `building_id` | 위험 요인·출동 여건·센서 / ui: `selectBuilding`, `flyTo` |
| Tool | `analyze_coverage` | `facility_type`, `region`, `radius_m`, `include_planned` | 커버리지·행정동별·우선 대상 / ui: `runCoverage`, `highlightBuildings` |
| Tool | `recommend_facility_sites` | `facility_type`(HYDRANT·AED), `count`, `region`, `radius_m` | 전후 커버리지·추천 입지(진행률 알림) / ui: `showSites`, `highlightBuildings` |
| Tool | `get_dashboard_stats` | — | 전체·행정동별 통계 / ui: `setColorMode` |
| Tool | `get_sensor_status` | `kind`, `alerts_only` | 경보 목록 / ui: `showLayer`, `flyTo` |
| Tool | `list_3d_models` | — | 모델 목록 / ui: `showLayer` |
| Resource | `twin://schema`, `twin://standards` | — | 데이터 사전, 분석 기준(근거 포함) |
| Prompt | `safety_briefing(region)` | — | 행정동 안전 브리핑 작성 지시 |
| HTTP | `GET /healthz` | — | 프로브용 |

모든 도구는 `readOnlyHint=true`이며, 결과는 `content`(LLM용 요약)와 `structuredContent{data, ui_actions}`로 나뉜다.

## 3. 에이전트 API (`ai-agent`)

`POST /agent/chat` `{message(≤2000자), sessionId?, context?: {selectedBuildingId}}` → `text/event-stream`

| 이벤트 | 데이터 |
|---|---|
| `tool_call` | `{id, name, args}` |
| `tool_result` | `{id, name, ok, summary}` |
| `ui_action` | 지도 액션(아래) |
| `token` | `{text}` — LLM 답변 스트리밍 조각 |
| `message` | `{content, streamed}` — 최종 답변 |
| `done` | `{mode: llm\|rule, model, sessionId}` |
| `error` | `{message}` |

`GET /agent/health` → `{status, mode, model, toolsLoaded}`

## 4. 지도 액션(ui_action) 스키마

| type | 필드 | 프론트엔드 동작 |
|---|---|---|
| `highlightBuildings` | `ids[]`, `label` | 해당 건물만 색상 유지, 나머지 흐리게, 카메라 맞춤 |
| `flyTo` | `lon`, `lat`, `height?` | 카메라 이동 |
| `selectBuilding` | `id` | 상세 패널 열기 |
| `runCoverage` | `facilityType`, `regionId?`, `radiusM?` | 분석 탭에서 커버리지 재실행(사각지대·반경 원 표시) |
| `showSites` | `result`(추천 결과) | 추천 입지·반경 원·라벨 표시 |
| `setColorMode` | `mode: risk\|use\|age` | 건물 색상 기준 변경 |
| `showLayer` | `layer`, `visible` | 레이어 토글 |
