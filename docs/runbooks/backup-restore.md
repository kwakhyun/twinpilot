# 런북: 백업·복구

## 백업 구성

| 항목 | 값 |
|---|---|
| 대상 | PostgreSQL 데이터베이스 3개(twinpilot·keycloak·agent) + 역할 정의(비밀번호 제외) |
| 방식 | CronJob `postgres-backup`: `pg_dump -Fc`(압축) → 목록 읽기로 손상 확인 → SHA256SUMS → Object Storage 업로드 → 업로드 파일 수 확인 |
| 주기 | 매일 03:17 KST(`spec.timeZone: Asia/Seoul`), 동시 실행 금지 |
| 위치 | `s3://<BACKUP_S3_BUCKET>/postgres/<UTC 시각>/{twinpilot,keycloak,agent}.dump, globals.sql, SHA256SUMS` |
| 보관 | `BACKUP_RETENTION_DAYS`(기본 14일). 이번 업로드를 확인한 뒤에만 오래된 백업을 지운다 |
| 감시 | 경보 `TwinPilotBackupMissing`(26시간 넘게 성공 없음), `TwinPilotBackupFailed` |
| 목표 | RPO 24시간(일일 백업). 더 짧게 하려면 관리형 DB나 WAL 아카이빙(PITR)을 쓴다 |

역할 비밀번호는 Secret이 원본이다. 복구 후 `postgres-bootstrap`이 Secret 값으로 다시 맞춘다.
3D 모델·가져오기 원본은 Object Storage 버킷(모델)에 있으므로 버킷 버전 관리나 복제로 따로 보호한다.

## 백업 확인

```bash
kubectl -n twinpilot get cronjob postgres-backup                      # LAST SCHEDULE / LAST SUCCESSFUL
kubectl -n twinpilot logs job/<최근 Job 이름> -c dump                  # 덤프 파일 목록
kubectl -n twinpilot logs job/<최근 Job 이름> -c upload                # "업로드 완료: … (5개)"
aws --endpoint-url https://kr.object.ncloudstorage.com s3 ls s3://<버킷>/postgres/
```

수동 실행: `kubectl -n twinpilot create job --from=cronjob/postgres-backup backup-manual-$(date +%s)`

## 복구

복구는 데이터베이스를 지우고 백업 시점으로 다시 만든다(`pg_restore --clean --if-exists --create`).
복구 시점 이후의 변경(분석 Job 이력, 계획 시설, 감사 로그, 대화 기록, Keycloak 사용자·세션 변경)은 사라진다.

1. **복구할 시각을 고른다.**
   ```bash
   aws --endpoint-url https://kr.object.ncloudstorage.com s3 ls s3://<버킷>/postgres/
   ```
2. **자동 동기화를 멈춘다(Argo CD).** 멈추지 않으면 레플리카를 줄여도 다시 늘린다.
   ```bash
   argocd app set twinpilot --sync-policy none
   ```
3. **DB에 붙은 워크로드를 내린다.** 레플리카가 0이면 HPA는 조정을 멈춘다.
   ```bash
   kubectl -n twinpilot scale deploy twin-api mcp-server ai-agent keycloak --replicas=0
   kubectl -n twinpilot wait --for=delete pod -l 'app.kubernetes.io/name in (twin-api,ai-agent,keycloak)' --timeout=120s
   ```
4. **복구 Job을 실행한다.** `infra/k8s/components/backup/restore-job.yaml`의 `BACKUP_TIMESTAMP` 두 곳을 고른 시각으로 바꾼다.
   ```bash
   kubectl -n twinpilot apply -f infra/k8s/components/backup/restore-job.yaml
   kubectl -n twinpilot logs -f job/postgres-restore -c download
   kubectl -n twinpilot logs -f job/postgres-restore -c restore     # 체크섬 OK → 복구: … → 복구 완료
   ```
   체크섬이 맞지 않으면 복구하지 않고 멈춘다. 다른 시각의 백업을 고른다.
5. **역할 비밀번호를 맞추고 워크로드를 올린다.**
   ```bash
   kubectl -n twinpilot delete job postgres-bootstrap --ignore-not-found
   argocd app set twinpilot --sync-policy automated --self-heal --auto-prune   # 동기화가 부트스트랩·레플리카를 되돌린다
   # Argo CD를 쓰지 않으면: kubectl apply -k infra/k8s/overlays/ncp
   ```
6. **점검한다.**
   - 로그인, 지도·통계 화면, 분석 1회
   - 건물·시설 수가 백업 시점과 같은지(아래 SQL)
   - `kubectl -n twinpilot delete job postgres-restore`
   ```bash
   kubectl -n twinpilot exec postgis-0 -- psql -U twin -d twinpilot -Atc \
     "select (select count(*) from building), (select count(*) from facility), (select max(version) from flyway_schema_history)"
   ```

## 새 클러스터로 복구(재해 복구)

1. [ncp-deploy.md](ncp-deploy.md) 1~7단계로 클러스터와 앱을 배포한다(`SEED_ENABLED: "false"`로 두면 합성 데이터를 적재하지 않는다).
2. 위 복구 절차 2~6단계를 그대로 실행한다. 부트스트랩이 만든 빈 DB를 백업으로 바꾼다.
3. Keycloak realm 서명키도 DB에 있으므로 복구 후 기존 사용자·클라이언트 설정이 그대로 돌아온다. 브라우저 세션은 다시 로그인해야 할 수 있다.

## 검증 기록

docker compose 스택에서 같은 스크립트로 확인했다(05 문서 §6).
덤프 3개 → S3 호환 저장소(adobe/s3mock) 업로드 → 보관 기간 지난 접두어 삭제 → 빈 PostGIS에 부트스트랩 SQL 적용 →
다운로드·체크섬 → 복구. 건물 2,309·시설 454·도로 간선 243·노드 143·센서 111·Flyway 8·측정값 48,840건과
파티션 5개가 원본과 같았고, 새 비밀번호로 keycloak 사용자가 복구된 DB에 접속했다.
