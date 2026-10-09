# 런북: 비밀 교체

모든 비밀은 `twinpilot-secrets`(Kubernetes Secret)에 있다. 교체할 때는 **사용하는 쪽이 새 값을 받아들이도록 먼저 바꾸고,
그다음 Secret을 바꿔 워크로드를 재시작**한다. 순서가 바뀌면 재시작한 파드가 인증에 실패한다.

```bash
# Secret 한 키만 바꾸기(나머지는 그대로)
kubectl -n twinpilot patch secret twinpilot-secrets --type merge -p "{\"stringData\":{\"<KEY>\":\"<새 값>\"}}"
```

| 비밀 | 쓰는 곳 | 교체 순서 |
|---|---|---|
| `DB_PASSWORD` | twin-api, 백업, 부트스트랩(PostgreSQL 슈퍼유저 `twin`) | ① DB 역할 변경 → ② Secret → ③ twin-api 재시작 |
| `KEYCLOAK_DB_PASSWORD`, `AGENT_DB_PASSWORD` | keycloak, ai-agent | ① Secret → ② 부트스트랩 Job 재실행(역할 비밀번호를 Secret 값으로 맞춤) → ③ keycloak·ai-agent 재시작 |
| `AGENT_CLIENT_SECRET`, `MCP_CLIENT_SECRET`, `SENSOR_GATEWAY_SECRET` | ai-agent, mcp-server, 센서 게이트웨이 | ① Keycloak 클라이언트 시크릿 변경 → ② Secret → ③ 해당 서비스 재시작 |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak 최초 기동 때만 | 최초 기동 후에는 쓰지 않는다. bootstrap 관리자는 비활성화하고 개인 관리자 계정을 쓴다 |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | twin-api(모델 저장소), 백업 | ① NCP에서 새 API 인증키 발급 → ② Secret → ③ twin-api 재시작(백업은 다음 실행부터) → ④ 이전 키 폐기 |
| `ANTHROPIC_API_KEY` | ai-agent | ① 새 키 발급 → ② Secret → ③ ai-agent 재시작 → ④ 이전 키 폐기 |

## DB 슈퍼유저(`DB_PASSWORD`)

PostgreSQL 이미지의 `POSTGRES_PASSWORD`는 데이터 디렉터리를 처음 만들 때만 쓰인다. 이미 있는 DB에는 직접 바꾼다.

```bash
NEW=$(openssl rand -hex 24)
kubectl -n twinpilot exec postgis-0 -- psql -U twin -d twinpilot -c "ALTER ROLE twin PASSWORD '$NEW'"
kubectl -n twinpilot patch secret twinpilot-secrets --type merge -p "{\"stringData\":{\"DB_PASSWORD\":\"$NEW\"}}"
kubectl -n twinpilot rollout restart deploy/twin-api
```

이미 열린 커넥션은 그대로 동작하므로, 재시작이 끝날 때까지 서비스는 끊기지 않는다.

## keycloak·agent DB 비밀번호

```bash
kubectl -n twinpilot patch secret twinpilot-secrets --type merge -p "{\"stringData\":{\"AGENT_DB_PASSWORD\":\"$(openssl rand -hex 24)\"}}"
kubectl -n twinpilot delete job postgres-bootstrap --ignore-not-found
argocd app sync twinpilot            # 또는 kubectl apply -k infra/k8s/overlays/ncp — 부트스트랩이 ALTER ROLE 실행
kubectl -n twinpilot wait --for=condition=complete job/postgres-bootstrap --timeout=300s
kubectl -n twinpilot rollout restart deploy/ai-agent      # keycloak이면 deploy/keycloak
```

## 서비스 계정 클라이언트 시크릿

realm은 처음 기동할 때만 가져온다(이미 있으면 건너뛴다). 그래서 Secret만 바꾸면 Keycloak의 값은 그대로이고
서비스가 토큰 교환에 실패한다. Keycloak 값을 먼저 바꾼다(관리 콘솔: Clients → 클라이언트 → Credentials, 또는 kcadm).

```bash
NEW=$(openssl rand -hex 32)
KC="kubectl -n twinpilot exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh"
$KC config credentials --config /tmp/kcadm.config --server http://localhost:8080/auth --realm master --user <관리자>
CID=$($KC get clients --config /tmp/kcadm.config -r twinpilot -q clientId=twinpilot-mcp --fields id --format csv --noquotes)
$KC update clients/$CID --config /tmp/kcadm.config -r twinpilot -s secret=$NEW
kubectl -n twinpilot exec deploy/keycloak -- rm -f /tmp/kcadm.config
kubectl -n twinpilot patch secret twinpilot-secrets --type merge -p "{\"stringData\":{\"MCP_CLIENT_SECRET\":\"$NEW\"}}"
kubectl -n twinpilot rollout restart deploy/mcp-server
```

Keycloak 값을 바꾼 순간부터 재시작이 끝날 때까지 해당 서비스의 토큰 교환이 실패한다(코파일럿 도구 호출 오류).
트래픽이 적은 시간에 한다. 검증: compose 스택에서 MCP 클라이언트 시크릿을 바꾸자 기존 서비스의 도구 호출이 실패했고,
새 값으로 재시작한 뒤 통과했다. Keycloak 컨테이너를 다시 만들어도 바꾼 값이 유지됐다(realm 가져오기를 건너뜀).

| 클라이언트 | Secret 키 | 재시작 대상 |
|---|---|---|
| `twinpilot-agent` | `AGENT_CLIENT_SECRET` | ai-agent |
| `twinpilot-mcp` | `MCP_CLIENT_SECRET` | mcp-server |
| `twinpilot-sensor-gateway` | `SENSOR_GATEWAY_SECRET` | 센서 게이트웨이(클러스터 밖) |

## 토큰 서명키

Keycloak realm 서명키(RS256)는 Realm settings → Keys에서 교체한다. 새 키 공급자를 더 높은 우선순위로 추가하고,
이전 키는 발급된 토큰이 모두 만료될 때까지(액세스 5분, SSO 유휴 30분, 최대 10시간) **검증용으로 남긴 뒤** 비활성화한다.
twin-api·mcp-server·ai-agent는 JWKS를 주기적으로 다시 받으므로 재시작할 필요가 없다.

## 유출이 의심될 때

1. 해당 비밀을 위 순서로 즉시 교체한다.
2. 사용자 토큰 유출이면 Keycloak에서 해당 사용자 세션을 로그아웃시키고(Users → Sessions), 필요하면 realm의 `Not before`를 갱신해 그 이전 토큰을 모두 무효화한다.
3. 감사 로그(`/api/v1/admin/audit`)와 접근 로그에서 해당 기간의 변경을 확인한다.
