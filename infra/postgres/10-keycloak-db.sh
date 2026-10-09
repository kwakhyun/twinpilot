#!/bin/bash
# Keycloak용 데이터베이스와 사용자를 만든다(데이터 볼륨을 처음 초기화할 때만 실행된다).
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE USER keycloak WITH PASSWORD '${KEYCLOAK_DB_PASSWORD}';
CREATE DATABASE keycloak OWNER keycloak;
SQL
