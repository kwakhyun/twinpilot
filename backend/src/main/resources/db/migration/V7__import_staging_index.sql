-- 대용량 적재(수십만 피처)에서 파일 내 중복 제거·REPLACE 비교가 순차 비교(O(n²))가 되지 않도록 색인한다.
CREATE INDEX import_staging_ext_idx ON import_staging (job_id, external_id) WHERE external_id IS NOT NULL;
