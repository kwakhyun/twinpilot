-- 인증 서버(keycloak)·AI 에이전트 대화 상태(agent)용 사용자와 데이터베이스를 만든다.
-- 여러 번 실행해도 안전하다(없을 때만 생성, 비밀번호는 매번 현재 Secret 값으로 맞춤 → 교체 지원).
-- docker compose: postgres 이미지의 /docker-entrypoint-initdb.d에서 최초 1회 실행
-- Kubernetes: postgres-bootstrap Job이 배포마다 실행(기존 볼륨에도 적용)
\set ON_ERROR_STOP on
\getenv kc_pw KEYCLOAK_DB_PASSWORD
\getenv agent_pw AGENT_DB_PASSWORD

SELECT 'CREATE ROLE keycloak LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak')\gexec
SELECT format('ALTER ROLE keycloak WITH LOGIN PASSWORD %L', :'kc_pw')\gexec
SELECT 'CREATE DATABASE keycloak OWNER keycloak' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak')\gexec

SELECT 'CREATE ROLE agent LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent')\gexec
SELECT format('ALTER ROLE agent WITH LOGIN PASSWORD %L', :'agent_pw')\gexec
SELECT 'CREATE DATABASE agent OWNER agent' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'agent')\gexec
