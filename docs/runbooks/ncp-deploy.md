# 런북: NCP(NKS) 배포

처음 배포부터 점검까지의 순서다. 각 단계의 완료 조건을 확인하고 다음 단계로 넘어간다.
이 작업 환경에서는 NCP 계정이 없어 아래 절차를 실제 클라우드에서 실행하지 못했다.
매니페스트는 kubeconform과 kube-apiserver 서버 측 dry-run으로, 백업·복구는 S3 호환 저장소로 검증했다(05 문서).

## 0. 준비물

| 항목 | 용도 |
|---|---|
| NCP 계정·API 인증키(Access/Secret Key) | Terraform, Container Registry 로그인, Object Storage |
| 도메인과 DNS 관리 권한 | 서비스 주소(예: `twin.example.go.kr`) |
| Certificate Manager 인증서 | ALB HTTPS(인증서 번호) |
| Git 저장소 | Argo CD가 매니페스트를 읽는다(CI도 같은 저장소) |
| Anthropic API 키(선택) | LLM 모드. 없으면 규칙 기반 플래너로 동작 |
| 도구 | terraform ≥ 1.6, kubectl, ncp-iam-authenticator, helm(Argo CD 설치용), aws CLI(Object Storage 점검용) |

## 1. 인프라(Terraform)

```bash
cd infra/terraform/ncp
cp terraform.tfvars.example terraform.tfvars        # 버킷 이름은 전역 고유
export TF_VAR_access_key=… TF_VAR_secret_key=…
terraform init && terraform validate && terraform plan -out tfplan
terraform apply tfplan
```

VPC, 노드용 사설 서브넷, LB 사설·공인 서브넷, NAT Gateway, NKS 클러스터(Cilium CNI)·노드풀(오토스케일),
Object Storage 버킷 2개(3D 모델·DB 백업)를 만든다.

- 완료 조건: `terraform output`에 `nks_cluster_uuid`, `models_bucket`, `backups_bucket`이 나온다.
- 이 작업 환경에서는 Terraform 레지스트리 접근이 막혀 `terraform fmt`만 확인했다. `validate`·`plan` 결과에서
  provider 인자 오류가 나면 사용하는 ncloud provider 문서와 대조해 고친다.

## 2. kubeconfig

```bash
ncp-iam-authenticator create-kubeconfig --region KR --clusterUuid <nks_cluster_uuid> --output kubeconfig.yaml
export KUBECONFIG=$PWD/kubeconfig.yaml
kubectl get nodes          # Ready 노드가 node_count만큼
```

## 3. 이미지

CI(`.github/workflows/ci.yml`)의 `images` 잡이 이미지 5종을 빌드하고 Trivy로 검사한 뒤 NCR에 올린다.
저장소 변수 `NCR_REGISTRY`(예: `twinpilot.kr.ncr.ntruss.com`)와 시크릿 `NCP_ACCESS_KEY`·`NCP_SECRET_KEY`를 등록하고
`v0.2.0` 태그를 푸시하면 `0.2.0` 태그로 올라간다.

```bash
git tag v0.2.0 && git push origin v0.2.0
```

`infra/k8s/overlays/ncp/kustomization.yaml`의 `images[].newName`을 실제 레지스트리 주소로 바꾼다.

## 4. 네임스페이스·Secret

```bash
kubectl apply -f infra/k8s/base/namespace.yaml
kubectl -n twinpilot create secret docker-registry ncr-cred --docker-server=<registry>.kr.ncr.ntruss.com \
  --docker-username=<ACCESS_KEY> --docker-password=<SECRET_KEY>
cp infra/k8s/overlays/ncp/secrets.env.example secrets.env     # 값 채우기(영숫자 DB 비밀번호: openssl rand -hex 24)
kubectl -n twinpilot create secret generic twinpilot-secrets --from-env-file=secrets.env
shred -u secrets.env
```

Secret을 Git에 두지 않는다. NCP Secret Manager나 External Secrets Operator를 쓰면 이 단계를 대신할 수 있다.

## 5. 환경 값

| 파일 | 바꿀 값 |
|---|---|
| `overlays/ncp/configmap-patch.yaml` | `PUBLIC_HOST`(한 곳만 바꾸면 Ingress host와 OIDC·Keycloak·CORS 주소가 함께 바뀐다), 버킷 이름, `SEED_ENABLED`, `SENSOR_SIMULATE` |
| `overlays/ncp/ingress-patch.yaml` | `alb.ingress.kubernetes.io/ssl-certificate-no` |
| `infra/argocd/*.yaml` | `REPLACE_WITH_REPO_URL` |

렌더링해서 확인한다.

```bash
kubectl kustomize infra/k8s/overlays/ncp | grep -n "twin.example.go.kr"   # 공개 주소가 모두 바뀌었는지
```

## 6. 모니터링 스택(kube-prometheus-stack)

NCP 오버레이는 ServiceMonitor·PrometheusRule을 포함하므로 CRD가 먼저 있어야 한다.

```bash
kubectl create namespace monitoring
kubectl -n monitoring create secret generic grafana-admin \
  --from-literal=admin-user=admin --from-literal=admin-password="$(openssl rand -hex 16)"
```

Argo CD를 쓰면 다음 단계에서 `platform-monitoring` 애플리케이션이 설치한다. Argo CD 없이 설치하려면
`infra/argocd/platform-monitoring.yaml`의 `valuesObject`를 values 파일로 옮겨 `helm install`한다.
경보 수신처(Slack·메일 등)는 `alertmanager.config`에 넣는다.

## 7. Argo CD

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm install argocd argo/argo-cd -n argocd --create-namespace
kubectl apply -n argocd -f infra/argocd/                     # 프로젝트 2개, 애플리케이션 2개
```

- 비공개 저장소면 Argo CD에 저장소 자격 증명을 등록한다.
- `postgres-bootstrap` Job이 Sync 훅으로 먼저 돌며 keycloak·agent DB를 만든다(PostGIS가 뜰 때까지 재시도).
- Argo CD 없이 적용할 때: `kubectl apply -k infra/k8s/overlays/ncp`. 부트스트랩 SQL을 바꾼 뒤 다시 적용하려면
  먼저 `kubectl -n twinpilot delete job postgres-bootstrap --ignore-not-found`(Job 템플릿은 바꿀 수 없다).

완료 조건

```bash
kubectl -n twinpilot get pods                 # 모두 Running/Ready, postgres-bootstrap Completed
kubectl -n twinpilot get ingress twinpilot    # ADDRESS에 ALB 주소
```

## 8. DNS·TLS

ALB 주소를 도메인의 CNAME으로 등록한다. `https://<PUBLIC_HOST>/`가 열리고 HTTP는 HTTPS로 넘어가야 한다.
SSE(센서·Job·코파일럿)가 길게 유지되므로 ALB 유휴 타임아웃을 콘솔에서 확인한다(끊겨도 클라이언트가 재연결한다).

## 9. Keycloak 초기 설정

1. `https://<PUBLIC_HOST>/auth/admin/`에 bootstrap 관리자(`admin` / `KEYCLOAK_ADMIN_PASSWORD`)로 로그인한다.
2. 개인 관리자 계정을 만들고 `realm-admin` 권한을 준 뒤 bootstrap 관리자를 비활성화한다.
3. 사용자를 만들고 역할(VIEWER·ANALYST·PLANNER·ADMIN)을 준다. 기관 SSO가 있으면 Identity Provider(OIDC·SAML)나
   LDAP 사용자 연동을 추가하고 그룹 → 역할 매핑을 둔다.
4. 데모 계정(viewer·analyst·planner·admin)은 운영 realm에 넣지 않는다(local 오버레이 전용).

## 10. 점검

```bash
BASE=https://<PUBLIC_HOST>
curl -sI $BASE/ | grep -i "strict-transport\|content-security"          # 보안 헤더
curl -s -o /dev/null -w "%{http_code}\n" $BASE/api/v1/stats/summary       # 401(인증 필요)
python3 e2e/oidc_login.py --base $BASE --user <분석 담당 계정> --password … > /tmp/token
python3 ai-agent/evals/run_eval.py --base $BASE --password …              # 평가 계정이 있을 때
kubectl -n twinpilot create job --from=cronjob/postgres-backup backup-smoke  # 백업 1회 실행 → Object Storage 확인
```

- Grafana `TwinPilot 운영 개요` 대시보드에 4개 서비스 지표가 들어오고, Prometheus Targets에서 twinpilot 대상이 모두 UP이어야 한다.
- 부하 점검은 `loadtest/` 스크립트를 운영 주소로 실행한다(전용 계정, 업무 시간 외).

## 11. 실제 데이터로 전환

[data-import.md](data-import.md) 절차로 건물·시설·도로를 적재한 뒤 `SEED_ENABLED: "false"`로 바꾼다.
센서 게이트웨이를 연결하면 `SENSOR_SIMULATE: "false"`로 바꾼다.

## 되돌리기

- 앱: Argo CD에서 이전 리비전으로 Sync(또는 Git revert). 이미지 태그를 고정해 두었으므로 이전 버전으로 그대로 돌아간다.
- DB 스키마: Flyway는 앞으로만 간다. 스키마 변경이 있는 버전을 되돌릴 때는 배포 직전 백업으로 복구한다([backup-restore.md](backup-restore.md)).
