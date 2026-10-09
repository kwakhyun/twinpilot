#!/bin/sh
# 데이터베이스별 pg_dump(커스텀 형식) + 역할 정의(비밀번호 제외)를 /backup/<시각>/에 만든다.
# 역할 비밀번호는 Secret이 원본이고 postgres-bootstrap이 복구 시 다시 넣으므로 백업에 담지 않는다.
set -eu
ts=$(date -u +%Y%m%dT%H%M%SZ)
out="/backup/$ts"
mkdir -p "$out"

pg_dumpall -h "$PGHOST" --globals-only --no-role-passwords -f "$out/globals.sql"
for db in $BACKUP_DATABASES; do
  pg_dump -h "$PGHOST" -d "$db" -Fc -Z 6 -f "$out/$db.dump"
  # 목록을 읽어 보며 손상 여부를 확인한다.
  pg_restore -l "$out/$db.dump" > /dev/null
done
(cd "$out" && sha256sum -- * > SHA256SUMS)
echo "$ts" > /backup/LATEST
ls -l "$out"
