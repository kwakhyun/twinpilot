-- analysis_job을 레플리카가 공유하는 작업 큐로 확장한다.
-- 작업 노드는 SELECT … FOR UPDATE SKIP LOCKED로 Job을 가져가고, 임대(locked_until)를 하트비트로 연장한다.
-- 임대가 만료된 Job(노드 중단)은 재시도 한도 안에서 다시 대기열로 돌아간다.
ALTER TABLE analysis_job
    ADD COLUMN message          text,
    ADD COLUMN attempts         integer      NOT NULL DEFAULT 0,
    ADD COLUMN max_attempts     integer      NOT NULL DEFAULT 3,
    ADD COLUMN locked_by        varchar(128),
    ADD COLUMN locked_until     timestamptz,
    ADD COLUMN run_after        timestamptz  NOT NULL DEFAULT now(),
    ADD COLUMN started_at       timestamptz,
    ADD COLUMN updated_at       timestamptz  NOT NULL DEFAULT now(),
    ADD COLUMN created_by       varchar(128),
    ADD COLUMN created_by_name  varchar(128),
    ADD COLUMN idempotency_key  varchar(128),
    ADD COLUMN cancel_requested boolean      NOT NULL DEFAULT false;

ALTER TABLE analysis_job
    ADD CONSTRAINT analysis_job_status_chk
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

CREATE INDEX analysis_job_queue_idx ON analysis_job (run_after, created_at) WHERE status = 'QUEUED';
CREATE INDEX analysis_job_lease_idx ON analysis_job (locked_until) WHERE status = 'RUNNING';
CREATE INDEX analysis_job_owner_idx ON analysis_job (created_by, created_at DESC);
CREATE INDEX analysis_job_finished_idx ON analysis_job (finished_at) WHERE finished_at IS NOT NULL;
-- 같은 사용자가 같은 Idempotency-Key로 다시 요청하면 기존 Job을 돌려준다(네트워크 재시도 중복 방지).
CREATE UNIQUE INDEX analysis_job_idem_idx ON analysis_job (created_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
