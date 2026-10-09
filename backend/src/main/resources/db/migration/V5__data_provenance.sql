-- 데이터 출처 추적: 외부 데이터(공공데이터 GeoJSON 등)를 적재할 때 원본 식별자로 갱신(upsert)할 수 있게 한다.
-- source는 데이터셋 이름(예: 'vworld-building-2026'), external_id는 원본의 고유 식별자다.
ALTER TABLE region   ADD COLUMN source varchar(64), ADD COLUMN external_id varchar(128), ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE road     ADD COLUMN source varchar(64), ADD COLUMN external_id varchar(128), ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE building ADD COLUMN source varchar(64), ADD COLUMN external_id varchar(128), ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE facility ADD COLUMN source varchar(64), ADD COLUMN external_id varchar(128), ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
                     ADD COLUMN created_by varchar(128);

CREATE UNIQUE INDEX region_source_ext_idx   ON region (source, external_id)   WHERE external_id IS NOT NULL;
CREATE UNIQUE INDEX road_source_ext_idx     ON road (source, external_id)     WHERE external_id IS NOT NULL;
CREATE UNIQUE INDEX building_source_ext_idx ON building (source, external_id) WHERE external_id IS NOT NULL;
CREATE UNIQUE INDEX facility_source_ext_idx ON facility (source, external_id) WHERE external_id IS NOT NULL;

ALTER TABLE model_asset ADD COLUMN created_by varchar(128), ADD COLUMN content_sha256 varchar(64);

-- 적재 스테이징: 원본 피처를 검증한 뒤 대상 테이블로 병합한다. Job이 끝나면 비운다.
CREATE UNLOGGED TABLE import_staging (
    job_id      uuid         NOT NULL,
    seq         integer      NOT NULL,
    external_id varchar(128),
    props       jsonb        NOT NULL,
    geom        geometry     NOT NULL,
    PRIMARY KEY (job_id, seq)
);
