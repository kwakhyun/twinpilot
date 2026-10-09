# 런북: 경보 대응

경보 규칙: `infra/observability/alerts.yml`(compose·Kubernetes 공통) + `infra/k8s/components/monitoring/generate.py`의
Kubernetes 전용 그룹. 모든 로그는 JSON이고 trace ID가 있으므로, 오류 로그의 trace ID로 Jaeger(또는 OTLP 백엔드)에서
요청 전체 경로(ai-agent → mcp-server → twin-api)를 확인한다.

공통 첫 단계

```bash
kubectl -n twinpilot get pods -o wide                     # 재시작·Pending·노드 쏠림
kubectl -n twinpilot top pods                             # CPU·메모리
kubectl -n twinpilot logs deploy/twin-api --since=15m | grep '"level":"ERROR"' | tail
kubectl -n twinpilot get events --sort-by=.lastTimestamp | tail -20
```

## TwinApiHighErrorRate (critical)

twin-api 5xx 비율이 5분 넘게 2%를 넘었다.

1. 응답 코드별로 나눈다: Grafana `API 5xx 비율`·`API p95 지연(경로별)` 패널, 또는 `sum by (status, uri)(rate(http_server_requests_seconds_count{job="twin-api",status=~"5.."}[5m]))`.
2. **503**이면 과부하다(DB 커넥션 대기 초과·쿼리 30초 초과). `DbPoolExhausted`와 PostGIS CPU를 본다.
   → twin-api 레플리카를 늘리기 전에 DB가 병목인지 확인한다. DB가 병목이면 레플리카를 늘려도 나아지지 않는다.
3. **500**이면 로그의 예외와 trace ID를 본다. 배포 직후면 Argo CD에서 이전 리비전으로 되돌린다.
4. 특정 URI에 몰리면 그 기능의 최근 변경·데이터(가져오기 직후 등)를 의심한다.

## TwinApiSlowRequests (warning)

URI별 p95가 10분 넘게 1초를 넘었다(SSE 스트림 제외).

1. 어떤 URI인지 확인한다(경보 레이블 `uri`).
2. `/analysis/coverage`·`/analysis/response`·`/stats/summary`는 캐시가 있다. 데이터 변경(가져오기·위험도 재계산·계획 시설)이
   잦으면 캐시가 계속 비워져 원래 비용(부하 시험 기준 수백 ms~1초대)이 든다.
3. PostgreSQL 느린 쿼리를 본다:
   ```bash
   kubectl -n twinpilot exec postgis-0 -- psql -U twin -d twinpilot -c \
     "select pid, now()-query_start as dur, state, left(query,120) from pg_stat_activity where datname='twinpilot' and state<>'idle' order by dur desc limit 10"
   ```
4. 가져오기 직후라면 통계가 오래됐을 수 있다: `ANALYZE building; ANALYZE facility;`

## DbPoolExhausted (critical)

twin-api가 커넥션을 기다리는 요청이 5분 넘게 5개를 넘었다.

1. 오래 걸리는 쿼리와 잠금을 본다(위 SQL, `pg_locks`). 장시간 Job(가져오기 병합)이 있는지 확인한다.
2. `max_connections`(250) 대비 사용량: `select datname, count(*) from pg_stat_activity group by 1`.
3. 일시적이면 클라이언트는 503 + Retry-After를 받는다. 지속되면 원인 쿼리를 끊는다: `select pg_cancel_backend(<pid>)`.

## JobQueueBacklog (warning) / JobFailures (warning)

```bash
kubectl -n twinpilot exec postgis-0 -- psql -U twin -d twinpilot -c \
  "select type, status, count(*), max(attempts) from analysis_job where created_at > now()-interval '1 hour' group by 1,2"
kubectl -n twinpilot exec postgis-0 -- psql -U twin -d twinpilot -c \
  "select id, type, attempts, left(error,160) from analysis_job where status='FAILED' order by finished_at desc limit 10"
```

- 대기열 적체: 워커 수 = 레플리카 × `JOB_CONCURRENCY`(기본 2). 큰 가져오기가 워커를 오래 잡고 있는지 본다.
  리스가 끝난 작업은 리퍼가 다시 넣으므로, `RUNNING`인데 진행률이 멈춘 작업은 워커 파드 상태를 확인한다.
- 실패: 오류 메시지별로 묶는다. "처리 시간 초과: …"는 쿼리 시간 초과라 재시도하지 않은 것이다(범위를 나눠 다시 실행).
  가져오기 실패는 관리 화면의 검증 보고서(오류 예시)를 본다.

## SensorIngestStalled (warning)

10분 넘게 수락된 센서 측정값이 없다.

1. 시뮬레이터 모드(`SENSOR_SIMULATE=true`)면 리더 레플리카가 살아 있는지 본다: `select * from leader_lease`.
2. 게이트웨이 연동이면 게이트웨이 로그와 `twinpilot_sensors_readings_total{result="rejected"}`(거절 사유: 알 수 없는 센서·시각 범위)를 본다.
3. 게이트웨이 토큰 오류(401)면 `SENSOR_GATEWAY_SECRET`과 Keycloak 클라이언트 시크릿이 같은지 확인한다.

## AgentLlmFallbacks (warning)

15분에 LLM 장애로 규칙 기반 응답 전환이 5회를 넘었다. 사용자는 답을 받지만 품질이 낮다.

1. 전환 사유는 `twinpilot_agent_llm_fallback_total{reason}`과 ai-agent 로그에 있다(`timeout`, `connection`, `status_429`, `status_5xx`).
2. `status_429`면 조직의 API 사용 한도를 확인한다. 토큰 사용량은 `twinpilot_agent_llm_tokens_total`.
3. Anthropic 상태 페이지를 확인한다. 장애가 길면 `AGENT_MODE=rule`로 바꿔 시간 초과를 기다리지 않게 할 수 있다.

## AgentErrors (warning) / McpToolErrors (warning)

1. `twinpilot_agent_chat_total{outcome}`로 유형을 나눈다: `timeout`(180초 초과), `error`, `init_error`(MCP 연결 실패).
   사용자별 한도 초과는 `twinpilot_agent_rate_limited_total`에 따로 센다.
2. MCP 도구 오류는 `twinpilot_mcp_tool_calls_total{tool, outcome}`(`ok`·`error`·`exception`)로 도구를 찾는다.
   mcp-server 로그에 401·토큰 교환 실패가 보이면: `MCP_CLIENT_SECRET`·`AGENT_CLIENT_SECRET`이 Keycloak 값과 같은지, Keycloak이 정상인지 본다.
3. 특정 도구만 실패하면 그 도구가 부르는 twin-api 엔드포인트 경보를 함께 본다.

## AuditWriteFailures (warning)

감사 기록을 DB에 쓰지 못해 로그로 대신 남겼다. DB 트랜잭션으로 묶은 작업(Job 제출·취소, 계획 시설, 모델 배치, 데이터 적재)은
감사 기록이 실패하면 작업 자체가 롤백되므로 이 경보에 해당하지 않는다. 파일 저장소가 끼는 모델 업로드·삭제만 해당한다.

1. twin-api 로그에서 `감사 로그 DB 기록 실패(로그로 대신 보존)`를 찾는다. 행위자·작업·대상·세부 내용이 그대로 있다.
2. 원인(DB 과부하·연결 실패)을 해소한 뒤, 필요하면 로그 내용을 `audit_log`에 수동으로 옮긴다.

## TwinPilotTargetDown (critical)

Prometheus가 5분 넘게 대상을 수집하지 못했다. 파드가 죽었거나 NetworkPolicy가 monitoring 네임스페이스를 막고 있다.

```bash
kubectl -n twinpilot get pods -l app.kubernetes.io/name=<job 레이블>
kubectl get ns monitoring --show-labels      # kubernetes.io/metadata.name=monitoring 이어야 허용 정책과 맞는다
```

## TwinPilotPodRestarting (warning)

15분에 3회 넘게 재시작했다. `kubectl -n twinpilot describe pod <pod>`의 Last State를 본다.

- `OOMKilled`: 메모리 한도를 올리거나 원인(큰 GeoJSON 레이어 요청 폭주, 큰 가져오기)을 찾는다. JVM은 `-XX:+ExitOnOutOfMemoryError`로 바로 재시작한다.
- 프로브 실패: 기동이 느린지(startupProbe), DB 연결 문제인지 로그를 본다. Keycloak은 realm `.well-known`이 200이 될 때까지 준비되지 않는다.

## TwinPilotBackupMissing (critical) / TwinPilotBackupFailed (warning)

```bash
kubectl -n twinpilot get jobs -l app.kubernetes.io/name=postgres-backup
kubectl -n twinpilot logs job/<실패한 Job> -c dump
kubectl -n twinpilot logs job/<실패한 Job> -c upload
```

- dump 실패: PostGIS 상태, `DB_PASSWORD`, 임시 볼륨 용량(`emptyDir` 20Gi)
- upload 실패: Object Storage 키(`S3_ACCESS_KEY`·`S3_SECRET_KEY`), 버킷 이름, NAT Gateway(외부 통신)
- 고친 뒤 수동 실행: `kubectl -n twinpilot create job --from=cronjob/postgres-backup backup-manual-$(date +%s)`

## TwinPilotDbVolumeFilling (warning)

PostGIS 볼륨 여유가 15% 미만이다.

1. 큰 테이블: `select relname, pg_size_pretty(pg_total_relation_size(oid)) from pg_class where relkind in ('r','p') order by pg_total_relation_size(oid) desc limit 10`
2. 센서 측정값이 크면 보관 기간(`SENSOR_RETENTION_DAYS`)을 줄인다(오래된 파티션은 통째로 지워진다).
3. 볼륨을 늘린다(NKS 블록 스토리지 CSI 확장 지원 여부 확인 후 PVC 용량 수정).
