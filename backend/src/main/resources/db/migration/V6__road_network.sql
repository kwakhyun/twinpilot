-- 도로망 그래프와 도로망 기반 출동 시간
-- road(중심선)를 교차점에서 분할해 노드·간선 그래프를 만든다(ST_Node). 일방통행·회전 제한은 반영하지 않는다(가정).
-- 최단경로 계산은 애플리케이션(다중 출발점 Dijkstra)이 하고, 결과(초)를 building.response_s에 저장한다.

CREATE TABLE road_node (
    id     serial PRIMARY KEY,
    geom_m geometry(Point, 5186) NOT NULL
);
CREATE INDEX road_node_geom_idx ON road_node USING gist (geom_m);

CREATE TABLE road_edge (
    id        serial PRIMARY KEY,
    road_id   integer REFERENCES road (id) ON DELETE CASCADE,
    source    integer NOT NULL REFERENCES road_node (id) ON DELETE CASCADE,
    target    integer NOT NULL REFERENCES road_node (id) ON DELETE CASCADE,
    length_m  double precision NOT NULL,
    speed_kmh double precision NOT NULL,
    cost_s    double precision NOT NULL,
    geom_m    geometry(LineString, 5186) NOT NULL
);
CREATE INDEX road_edge_geom_idx ON road_edge USING gist (geom_m);

-- 그래프 버전. 도로가 바뀌면(roads_hash) 다시 만든다. 레플리카는 버전으로 캐시를 무효화한다.
CREATE TABLE road_network_state (
    id         integer PRIMARY KEY CHECK (id = 1),
    version    bigint      NOT NULL,
    roads_hash text        NOT NULL,
    nodes      integer     NOT NULL,
    edges      integer     NOT NULL,
    built_at   timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE building
    ADD COLUMN response_s          double precision,
    ADD COLUMN response_station_id integer,
    ADD COLUMN road_access_m       double precision;

CREATE OR REPLACE FUNCTION road_network_hash() RETURNS text
    LANGUAGE sql STABLE AS
$$
SELECT coalesce(md5(string_agg(id::text || ':' || width_m::text || ':' || md5(ST_AsBinary(geom_m)), ',' ORDER BY id)), '')
  FROM road
$$;

-- 도로 폭으로 주행속도를 정한다(가정값은 애플리케이션 설정에서 넘긴다).
CREATE OR REPLACE FUNCTION rebuild_road_network(wide_kmh double precision, medium_kmh double precision,
                                                narrow_kmh double precision)
    RETURNS integer
    LANGUAGE plpgsql AS
$$
DECLARE
    n_edges integer;
    n_nodes integer;
BEGIN
    DELETE FROM road_edge;
    DELETE FROM road_node;

    CREATE TEMP TABLE tmp_seg ON COMMIT DROP AS
    SELECT g, ST_SnapToGrid(ST_StartPoint(g), 0.01) AS s, ST_SnapToGrid(ST_EndPoint(g), 0.01) AS e
      FROM (SELECT (ST_Dump(ST_Node(ST_Collect(ST_SnapToGrid(geom_m, 0.01))))).geom AS g FROM road) d
     WHERE ST_Length(g) > 0.05;

    INSERT INTO road_node (geom_m)
    SELECT DISTINCT p FROM (SELECT s AS p FROM tmp_seg UNION SELECT e FROM tmp_seg) x;
    GET DIAGNOSTICS n_nodes = ROW_COUNT;

    INSERT INTO road_edge (road_id, source, target, length_m, speed_kmh, cost_s, geom_m)
    SELECT r.id, ns.id, ne.id, ST_Length(t.g), sp.kmh, ST_Length(t.g) / (sp.kmh / 3.6), t.g
      FROM tmp_seg t
      JOIN road_node ns ON ns.geom_m = t.s
      JOIN road_node ne ON ne.geom_m = t.e
      CROSS JOIN LATERAL (SELECT rd.id, rd.width_m FROM road rd
                           ORDER BY rd.geom_m <-> ST_LineInterpolatePoint(t.g, 0.5) LIMIT 1) r
      CROSS JOIN LATERAL (SELECT CASE WHEN r.width_m >= 20 THEN wide_kmh
                                      WHEN r.width_m >= 12 THEN medium_kmh
                                      ELSE narrow_kmh END AS kmh) sp;
    GET DIAGNOSTICS n_edges = ROW_COUNT;

    INSERT INTO road_network_state (id, version, roads_hash, nodes, edges, built_at)
    VALUES (1, 1, road_network_hash(), n_nodes, n_edges, now())
    ON CONFLICT (id) DO UPDATE SET version = road_network_state.version + 1, roads_hash = EXCLUDED.roads_hash,
                                   nodes = EXCLUDED.nodes, edges = EXCLUDED.edges, built_at = now();
    RETURN n_edges;
END
$$;

-- 위험도 재계산: 도로망 출동 시간(response_s)이 있으면 그것을, 없으면 직선거리 근사를 쓴다.
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
    ), response AS (
        SELECT b.id,
               coalesce(b.response_s / 60.0, near.station_m * detour / speed_m_per_min) AS minutes,
               CASE WHEN b.response_s IS NULL THEN 'STRAIGHT_LINE' ELSE 'ROAD_NETWORK' END AS method
          FROM building b JOIN near ON near.id = b.id
    ), factor AS (
        SELECT b.id,
               LEAST(GREATEST(extract(year FROM current_date)::int - coalesce(b.built_year, 1990), 0) / 50.0, 1) AS age_f,
               CASE b.structure WHEN 'WOOD' THEN 1.0 WHEN 'MASONRY' THEN 0.8 WHEN 'STEEL' THEN 0.5 ELSE 0.3 END AS struct_f,
               LEAST(b.floors / 30.0, 1) AS height_f,
               LEAST(coalesce(near.hydrant_m, 400) / 200.0, 1) AS hydrant_f,
               LEAST(coalesce(response.minutes, 14) / 14.0, 1) AS response_f,
               near.hydrant_m,
               near.station_m,
               response.minutes,
               response.method
          FROM building b
          JOIN near ON near.id = b.id
          JOIN response ON response.id = b.id
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
                   'responseMin', round(minutes::numeric, 1),
                   'responseMethod', method,
                   'roadAccessM', round(b.road_access_m::numeric, 1)),
           updated_at   = now()
      FROM factor
     WHERE factor.id = b.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END
$$;
