#!/usr/bin/env python3
"""docker compose용 관측성 설정(infra/observability)에서 Kubernetes 모니터링 리소스를 만든다.

  python3 infra/k8s/components/monitoring/generate.py          # 생성
  python3 infra/k8s/components/monitoring/generate.py --check  # 원본과 어긋나면 종료 코드 1(CI)

- prometheusrule.yaml: alerts.yml의 규칙 그룹 + Kubernetes 전용 그룹(백업·재시작·볼륨)
- dashboards/twinpilot-overview.json: Grafana 대시보드 사본(사이드카가 ConfigMap으로 읽는다)
표준 라이브러리만 쓴다(alerts.yml을 들여쓰기만 바꿔 그대로 옮긴다).
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OBS = HERE.parents[2] / "observability"

HEADER = """\
# 자동 생성 파일: infra/k8s/components/monitoring/generate.py로 만든다(직접 고치지 않는다).
# 원본: infra/observability/alerts.yml(+ 아래 twinpilot-k8s 그룹)
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: twinpilot
  labels:
    release: kube-prometheus-stack
spec:
"""

K8S_GROUP = """\
    - name: twinpilot-k8s
      rules:
        - alert: TwinPilotTargetDown
          expr: up{namespace="twinpilot"} == 0
          for: 5m
          labels: { severity: critical }
          annotations:
            summary: "메트릭 대상 응답 없음: {{ $labels.job }} ({{ $labels.pod }})"
        - alert: TwinPilotPodRestarting
          expr: increase(kube_pod_container_status_restarts_total{namespace="twinpilot"}[15m]) > 3
          labels: { severity: warning }
          annotations:
            summary: "컨테이너 재시작 반복: {{ $labels.pod }}/{{ $labels.container }}"
        - alert: TwinPilotBackupMissing
          expr: time() - max(kube_cronjob_status_last_successful_time{namespace="twinpilot",cronjob="postgres-backup"}) > 26 * 3600
          for: 30m
          labels: { severity: critical }
          annotations:
            summary: "DB 백업이 26시간 넘게 성공하지 않음"
            runbook: docs/runbooks/backup-restore.md
        - alert: TwinPilotBackupFailed
          expr: max(kube_job_status_failed{namespace="twinpilot",job_name=~"postgres-backup-.*"}) > 0
          labels: { severity: warning }
          annotations:
            summary: "DB 백업 Job 실패: {{ $labels.job_name }}"
            runbook: docs/runbooks/backup-restore.md
        - alert: TwinPilotDbVolumeFilling
          expr: |
            kubelet_volume_stats_available_bytes{namespace="twinpilot",persistentvolumeclaim=~"data-postgis-.*"}
              / kubelet_volume_stats_capacity_bytes{namespace="twinpilot",persistentvolumeclaim=~"data-postgis-.*"} < 0.15
          for: 15m
          labels: { severity: warning }
          annotations:
            summary: "PostGIS 볼륨 여유 공간 15% 미만"
"""


def render_rules() -> str:
    lines = (OBS / "alerts.yml").read_text(encoding="utf-8").splitlines()
    body = [line for line in lines if line.strip() and not line.lstrip().startswith("#")]
    if not body or body[0] != "groups:":
        raise SystemExit("alerts.yml은 최상위 'groups:'로 시작해야 한다")
    return HEADER + "\n".join("  " + line for line in body) + "\n" + K8S_GROUP


def outputs() -> dict[Path, str]:
    dashboard = (OBS / "grafana" / "dashboards" / "twinpilot-overview.json").read_text(encoding="utf-8")
    return {
        HERE / "prometheusrule.yaml": render_rules(),
        HERE / "dashboards" / "twinpilot-overview.json": dashboard,
    }


def main() -> int:
    check = "--check" in sys.argv[1:]
    stale = []
    for path, content in outputs().items():
        current = path.read_text(encoding="utf-8") if path.exists() else None
        if current == content:
            continue
        if check:
            stale.append(path.relative_to(HERE))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            print(f"생성: {path.relative_to(HERE)}")
    if stale:
        print("원본과 다르다(generate.py를 다시 실행한다): " + ", ".join(map(str, stale)), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
