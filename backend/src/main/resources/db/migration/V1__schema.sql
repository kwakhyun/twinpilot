-- TwinPilot 공간 스키마
-- 저장 좌표계: EPSG:4326(WGS84). 미터 단위 연산용 EPSG:5186(Korea 2000 / Central Belt 2010) 생성 컬럼을 함께 둔다.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE region (
    id        serial PRIMARY KEY,
    code      varchar(32)  NOT NULL UNIQUE,
    name      varchar(64)  NOT NULL,
    zone_type varchar(32)  NOT NULL CHECK (zone_type IN ('RESIDENTIAL', 'COMMERCIAL', 'INDUSTRIAL', 'GREEN', 'OTHER')),
    geom      geometry(MultiPolygon, 4326) NOT NULL,
    geom_m    geometry(MultiPolygon, 5186) GENERATED ALWAYS AS (ST_Transform(geom, 5186)) STORED
);
CREATE INDEX region_geom_idx ON region USING gist (geom);
CREATE INDEX region_geom_m_idx ON region USING gist (geom_m);

CREATE TABLE road (
    id      serial PRIMARY KEY,
    name    varchar(64),
    width_m double precision NOT NULL DEFAULT 10,
    geom    geometry(LineString, 4326) NOT NULL,
    geom_m  geometry(LineString, 5186) GENERATED ALWAYS AS (ST_Transform(geom, 5186)) STORED
);
CREATE INDEX road_geom_m_idx ON road USING gist (geom_m);

-- 건축물대장 표제부 필드(주용도, 구조, 지상/지하층수, 높이, 사용승인연도)에 대응
CREATE TABLE building (
    id              serial PRIMARY KEY,
    name            varchar(128),
    use_type        varchar(32)  NOT NULL,
    structure       varchar(16)  NOT NULL CHECK (structure IN ('RC', 'STEEL', 'MASONRY', 'WOOD')),
    floors          integer      NOT NULL CHECK (floors >= 0),
    basement_floors integer      NOT NULL DEFAULT 0,
    height_m        double precision NOT NULL,
    built_year      integer,
    region_id       integer REFERENCES region (id),
    risk_score      double precision,
    risk_factors    jsonb,
    geom            geometry(MultiPolygon, 4326) NOT NULL,
    geom_m          geometry(MultiPolygon, 5186) GENERATED ALWAYS AS (ST_Transform(geom, 5186)) STORED
);
CREATE INDEX building_geom_idx ON building USING gist (geom);
CREATE INDEX building_geom_m_idx ON building USING gist (geom_m);
CREATE INDEX building_region_idx ON building (region_id);
CREATE INDEX building_risk_idx ON building (risk_score DESC);

-- 소방용수시설(지상식/지하식/급수탑), AED, 소방서. PLANNED는 의사결정용 가상 배치
CREATE TABLE facility (
    id             serial PRIMARY KEY,
    type           varchar(16) NOT NULL CHECK (type IN ('HYDRANT', 'AED', 'FIRE_STATION')),
    subtype        varchar(32),
    name           varchar(128),
    status         varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'PLANNED')),
    installed_year integer,
    properties     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    geom           geometry(Point, 4326) NOT NULL,
    geom_m         geometry(Point, 5186) GENERATED ALWAYS AS (ST_Transform(geom, 5186)) STORED
);
CREATE INDEX facility_geom_m_idx ON facility USING gist (geom_m);
CREATE INDEX facility_type_idx ON facility (type, status);

CREATE TABLE bus_route (
    id   serial PRIMARY KEY,
    name varchar(64) NOT NULL,
    geom geometry(LineString, 4326) NOT NULL
);

CREATE TABLE sensor (
    id          serial PRIMARY KEY,
    code        varchar(32) NOT NULL UNIQUE,
    kind        varchar(32) NOT NULL CHECK (kind IN ('HYDRANT_PRESSURE', 'AIR_QUALITY')),
    facility_id integer REFERENCES facility (id),
    route_id    integer REFERENCES bus_route (id),
    properties  jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE sensor_reading (
    id        bigserial PRIMARY KEY,
    sensor_id integer     NOT NULL REFERENCES sensor (id),
    ts        timestamptz NOT NULL,
    value     jsonb       NOT NULL,
    geom      geometry(Point, 4326)
);
CREATE INDEX sensor_reading_sensor_ts_idx ON sensor_reading (sensor_id, ts DESC);

CREATE TABLE model_asset (
    id                uuid PRIMARY KEY,
    name              varchar(128) NOT NULL,
    status            varchar(16)  NOT NULL CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    error             text,
    format            varchar(16),
    original_filename varchar(255),
    size_bytes        bigint,
    storage_key       varchar(255),
    stats             jsonb,
    lon               double precision,
    lat               double precision,
    height_m          double precision NOT NULL DEFAULT 0,
    heading_deg       double precision NOT NULL DEFAULT 0,
    scale             double precision NOT NULL DEFAULT 1,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE analysis_job (
    id          uuid PRIMARY KEY,
    type        varchar(32) NOT NULL,
    status      varchar(16) NOT NULL,
    progress    integer     NOT NULL DEFAULT 0,
    params      jsonb,
    result      jsonb,
    error       text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

-- 화재위험도(0~100) 재계산. 요인별 기여도를 risk_factors에 남겨 설명 가능하게 한다.
--   노후도 30 + 구조 20 + 층수 15 + 최근접 소화전 거리 20 + 소방서 도달 추정시간 15
--   도달시간(분) = 직선거리 × 우회계수 ÷ 주행속도(m/분)
CREATE OR REPLACE FUNCTION refresh_building_risk(speed_m_per_min double precision DEFAULT 500,
                                                 detour double precision DEFAULT 1.3)
    RETURNS integer
    LANGUAGE plpgsql AS
$$
DECLARE
    n integer;
BEGIN
    WITH near AS (
        SELECT b.id,
               (SELECT ST_Distance(b.geom_m, f.geom_m)
                  FROM facility f
                 WHERE f.type = 'HYDRANT' AND f.status = 'ACTIVE'
                 ORDER BY f.geom_m <-> b.geom_m
                 LIMIT 1) AS hydrant_m,
               (SELECT ST_Distance(b.geom_m, f.geom_m)
                  FROM facility f
                 WHERE f.type = 'FIRE_STATION' AND f.status = 'ACTIVE'
                 ORDER BY f.geom_m <-> b.geom_m
                 LIMIT 1) AS station_m
          FROM building b
    ), factor AS (
        SELECT b.id,
               LEAST(GREATEST(extract(year FROM current_date)::int - coalesce(b.built_year, 1990), 0) / 50.0, 1) AS age_f,
               CASE b.structure WHEN 'WOOD' THEN 1.0 WHEN 'MASONRY' THEN 0.8 WHEN 'STEEL' THEN 0.5 ELSE 0.3 END AS struct_f,
               LEAST(b.floors / 30.0, 1) AS height_f,
               LEAST(coalesce(near.hydrant_m, 400) / 200.0, 1) AS hydrant_f,
               LEAST((coalesce(near.station_m, 5000) * detour / speed_m_per_min) / 14.0, 1) AS response_f,
               near.hydrant_m,
               near.station_m
          FROM building b
          JOIN near ON near.id = b.id
    )
    UPDATE building b
       SET risk_score   = round((30 * age_f + 20 * struct_f + 15 * height_f + 20 * hydrant_f + 15 * response_f)::numeric, 1),
           risk_factors = jsonb_build_object(
                   'age', round((30 * age_f)::numeric, 1),
                   'structure', round((20 * struct_f)::numeric, 1),
                   'height', round((15 * height_f)::numeric, 1),
                   'hydrant', round((20 * hydrant_f)::numeric, 1),
                   'response', round((15 * response_f)::numeric, 1),
                   'nearestHydrantM', round(hydrant_m::numeric, 1),
                   'nearestStationM', round(station_m::numeric, 1),
                   'responseMin', round((station_m * detour / speed_m_per_min)::numeric, 1))
      FROM factor
     WHERE factor.id = b.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END
$$;
