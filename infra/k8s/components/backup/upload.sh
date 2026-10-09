#!/bin/bash
# dump.sh 결과를 s3://<버킷>/postgres/<시각>/에 올리고, 보관 기간이 지난 백업을 지운다.
# 오래된 백업은 이번 업로드가 검증된 뒤에만 지운다(set -e).
set -euo pipefail
ts=$(cat /backup/LATEST)
dest="s3://${BACKUP_S3_BUCKET}/postgres/${ts}/"
s3() { aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3 "$@"; }

s3 cp --recursive --only-show-errors "/backup/${ts}/" "$dest"

# 올린 파일 수가 로컬과 같은지 확인한다.
local_count=$(find "/backup/${ts}" -type f | wc -l)
remote_count=$(s3 ls "$dest" | wc -l)
if [ "$local_count" -ne "$remote_count" ]; then
  echo "업로드 검증 실패: 로컬 ${local_count}개, 원격 ${remote_count}개" >&2
  exit 1
fi
echo "업로드 완료: ${dest} (${remote_count}개)"

cutoff=$(date -u -d "-${BACKUP_RETENTION_DAYS} days" +%Y%m%dT%H%M%SZ)
s3 ls "s3://${BACKUP_S3_BUCKET}/postgres/" | awk '$1 == "PRE" {print $2}' | tr -d / | while read -r prefix; do
  if [[ "$prefix" < "$cutoff" ]]; then
    echo "보관 기간 경과 삭제: ${prefix}"
    s3 rm --recursive --only-show-errors "s3://${BACKUP_S3_BUCKET}/postgres/${prefix}/"
  fi
done
