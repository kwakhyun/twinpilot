-- 센서 수집 구조
-- 1) sensor_latest: 센서별 최신값(레플리카가 공유하는 현재 상태). 더 늦은 ts만 덮어쓴다.
-- 2) sensor_reading: 일 단위 RANGE 파티션. 보존 기간이 지난 파티션은 DROP으로 지운다(대량 DELETE 없음).
--    파티션은 애플리케이션(SensorPartitionManager)이 미리 만들고, 범위 밖 행은 DEFAULT 파티션이 받는다.
-- 3) leader_lease: 레플리카 중 하나만 실행해야 하는 작업(데모 시뮬레이터)의 임대.

CREATE TABLE sensor_latest (
    sensor_id   integer PRIMARY KEY REFERENCES sensor (id) ON DELETE CASCADE,
    ts          timestamptz  NOT NULL,
    value       jsonb        NOT NULL,
    status      varchar(16)  NOT NULL,
    geom        geometry(Point, 4326),
    received_at timestamptz  NOT NULL DEFAULT now()
);

ALTER TABLE sensor_reading RENAME TO sensor_reading_legacy;

CREATE TABLE sensor_reading (
    sensor_id integer      NOT NULL REFERENCES sensor (id) ON DELETE CASCADE,
    ts        timestamptz  NOT NULL,
    value     jsonb        NOT NULL,
    status    varchar(16),
    geom      geometry(Point, 4326),
    PRIMARY KEY (sensor_id, ts)
) PARTITION BY RANGE (ts);

CREATE TABLE sensor_reading_default PARTITION OF sensor_reading DEFAULT;

INSERT INTO sensor_reading (sensor_id, ts, value, geom)
SELECT sensor_id, ts, value, geom FROM sensor_reading_legacy
ON CONFLICT DO NOTHING;

DROP TABLE sensor_reading_legacy;

CREATE TABLE leader_lease (
    name   varchar(64) PRIMARY KEY,
    holder varchar(128) NOT NULL,
    until  timestamptz  NOT NULL
);
