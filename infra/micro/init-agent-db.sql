-- 경량판: AI 에이전트 대화 상태(agent) 사용자·DB만 만든다(Keycloak DB는 쓰지 않는다).
\set ON_ERROR_STOP on
\getenv agent_pw AGENT_DB_PASSWORD
SELECT 'CREATE ROLE agent LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent')\gexec
SELECT format('ALTER ROLE agent WITH LOGIN PASSWORD %L', :'agent_pw')\gexec
SELECT 'CREATE DATABASE agent OWNER agent' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'agent')\gexec
