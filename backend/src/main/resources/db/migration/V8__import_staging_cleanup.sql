-- 작업 노드가 적재 도중 중단되면 그 시도의 스테이징 행이 남는다. 생성 시각으로 오래된 행을 정리한다.
ALTER TABLE import_staging ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX import_staging_created_idx ON import_staging (created_at);
