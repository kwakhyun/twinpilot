#!/bin/sh
# 복구용: 체크섬을 확인한 뒤 데이터베이스를 지우고 백업 시점으로 다시 만든다.
# 실행 전 해당 데이터베이스에 접속한 워크로드를 모두 내려야 한다(DROP DATABASE). 절차: docs/runbooks/backup-restore.md
set -eu
: "${BACKUP_TIMESTAMP:?복구할 백업 시각을 지정한다}"
dir="/backup/$BACKUP_TIMESTAMP"
(cd "$dir" && sha256sum -c SHA256SUMS)
for db in $BACKUP_DATABASES; do
  echo "복구: $db"
  pg_restore -h "$PGHOST" -d postgres --clean --if-exists --create --exit-on-error "$dir/$db.dump"
done
echo "복구 완료: $BACKUP_TIMESTAMP ($BACKUP_DATABASES)"
