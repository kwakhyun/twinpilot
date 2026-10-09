#!/bin/bash
# 데이터 볼륨을 처음 초기화할 때만 실행된다.
# - keycloak: 인증 서버 전용 DB·사용자
# - agent: AI 에이전트 대화 체크포인트(LangGraph) 전용 DB
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE USER keycloak WITH PASSWORD '${KEYCLOAK_DB_PASSWORD}';
CREATE DATABASE keycloak OWNER keycloak;
CREATE USER agent WITH PASSWORD '${AGENT_DB_PASSWORD}';
CREATE DATABASE agent OWNER agent;
SQL
