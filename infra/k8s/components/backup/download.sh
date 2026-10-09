#!/bin/bash
# 복구용: s3://<버킷>/postgres/<BACKUP_TIMESTAMP>/를 /backup/<BACKUP_TIMESTAMP>/로 받는다.
set -euo pipefail
: "${BACKUP_TIMESTAMP:?복구할 백업 시각(예: 20261009T181700Z)을 지정한다}"
aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3 cp --recursive --only-show-errors \
  "s3://${BACKUP_S3_BUCKET}/postgres/${BACKUP_TIMESTAMP}/" "/backup/${BACKUP_TIMESTAMP}/"
ls -l "/backup/${BACKUP_TIMESTAMP}"
