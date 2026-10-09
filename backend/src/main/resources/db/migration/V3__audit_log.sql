-- 감사 로그: 데이터·설정을 바꾸는 작업(계획 시설, 3D 모델, Job, 데이터 적재)의 주체와 내용을 남긴다.
CREATE TABLE audit_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz  NOT NULL DEFAULT now(),
    actor_sub   varchar(128) NOT NULL,
    actor_name  varchar(128),
    action      varchar(64)  NOT NULL,
    target_type varchar(32),
    target_id   varchar(64),
    details     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    client_ip   varchar(64),
    trace_id    varchar(32)
);
CREATE INDEX audit_log_ts_idx ON audit_log (ts DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_sub, ts DESC);
CREATE INDEX audit_log_action_idx ON audit_log (action, ts DESC);
