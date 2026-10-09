package io.twinpilot.api.building;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.twinpilot.api.building.BuildingDtos.BuildingSummary;
import io.twinpilot.api.building.BuildingDtos.NearbyFacility;
import io.twinpilot.api.common.GeoJson;
import io.twinpilot.api.common.RadiusPolicy;

@Repository
public class BuildingRepository {

    private static final Map<String, String> SORTS = Map.of(
            "risk", "b.risk_score DESC NULLS LAST, b.id",
            "age", "b.built_year ASC NULLS LAST, b.id",
            "floors", "b.floors DESC, b.id",
            "height", "b.height_m DESC, b.id",
            "id", "b.id");

    private static final String SUMMARY_COLUMNS = """
            b.id, b.name, b.use_type, b.structure, b.floors, b.basement_floors, b.height_m, b.built_year, b.region_id,
            r.name AS region_name, b.risk_score, b.risk_factors::text AS risk_factors,
            ST_X(ST_PointOnSurface(b.geom)) AS lon, ST_Y(ST_PointOnSurface(b.geom)) AS lat""";

    private final JdbcClient jdbc;
    private final RadiusPolicy radius;

    public BuildingRepository(JdbcClient jdbc, RadiusPolicy radius) {
        this.jdbc = jdbc;
        this.radius = radius;
    }

    /** 3D 렌더링용 FeatureCollection. 필터는 모두 바인딩 파라미터로 전달된다. */
    public String featureCollection(BuildingFilter filter) {
        Map<String, Object> p = new HashMap<>();
        String where = filter.where(p, radius);
        String sql = GeoJson.collect("""
                json_build_object('type', 'Feature', 'id', b.id,
                    'geometry', ST_AsGeoJSON(b.geom, 7)::json,
                    'properties', json_build_object('name', b.name, 'useType', b.use_type, 'structure', b.structure,
                        'floors', b.floors, 'heightM', b.height_m, 'builtYear', b.built_year,
                        'regionId', b.region_id, 'risk', b.risk_score))""",
                "FROM building b LEFT JOIN region r ON r.id = b.region_id " + where, "b.id");
        return jdbc.sql(sql).params(p).query(String.class).single();
    }

    public long count(BuildingFilter filter) {
        Map<String, Object> p = new HashMap<>();
        String where = filter.where(p, radius);
        return jdbc.sql("SELECT count(*) FROM building b LEFT JOIN region r ON r.id = b.region_id " + where)
                .params(p).query(Long.class).single();
    }

    public record Aggregate(long total, Double avgRisk, Double maxRisk) {
    }

    public Aggregate aggregate(BuildingFilter filter) {
        Map<String, Object> p = new HashMap<>();
        String where = filter.where(p, radius);
        return jdbc.sql("""
                        SELECT count(*) AS total, round(avg(b.risk_score)::numeric, 1) AS avg_risk, max(b.risk_score) AS max_risk
                        FROM building b LEFT JOIN region r ON r.id = b.region_id
                        """ + where)
                .params(p)
                .query((rs, i) -> new Aggregate(rs.getLong("total"), getDouble(rs, "avg_risk"),
                        getDouble(rs, "max_risk")))
                .single();
    }

    public List<BuildingSummary> search(BuildingFilter filter, String sort, int limit) {
        String orderBy = SORTS.get(sort);
        if (orderBy == null) {
            throw new IllegalArgumentException("sort는 " + SORTS.keySet() + " 중 하나여야 합니다.");
        }
        Map<String, Object> p = new HashMap<>();
        String where = filter.where(p, radius);
        p.put("limit", Math.clamp(limit, 1, 5000));
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM building b LEFT JOIN region r ON r.id = b.region_id "
                        + where + " ORDER BY " + orderBy + " LIMIT :limit")
                .params(p)
                .query((rs, i) -> summary(rs))
                .list();
    }

    /** responseS·responseStation은 도로망 출동 시간(REFRESH_RISK가 계산). 없으면 null. */
    public record BuildingRow(BuildingSummary summary, int basementFloors, String footprint, Double responseS,
                              String responseStation, Double roadAccessM) {
    }

    public Optional<BuildingRow> findById(int id) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + ", ST_AsGeoJSON(b.geom, 7) AS footprint,"
                        + " b.response_s, st.name AS response_station, b.road_access_m"
                        + " FROM building b LEFT JOIN region r ON r.id = b.region_id"
                        + " LEFT JOIN facility st ON st.id = b.response_station_id WHERE b.id = :id")
                .param("id", id)
                .query((rs, i) -> new BuildingRow(summary(rs), rs.getInt("basement_floors"), rs.getString("footprint"),
                        (Double) rs.getObject("response_s"), rs.getString("response_station"),
                        (Double) rs.getObject("road_access_m")))
                .optional();
    }

    /** KNN(<->)으로 후보를 좁힌 뒤 정확한 거리로 다시 정렬한다. */
    public List<NearbyFacility> nearest(int buildingId, String type, int limit) {
        return jdbc.sql("""
                        SELECT * FROM (
                            SELECT f.id, f.type, f.subtype, f.name, f.status,
                                   ST_Distance(f.geom_m, b.geom_m) AS distance_m, ST_X(f.geom) AS lon, ST_Y(f.geom) AS lat
                              FROM building b
                              JOIN LATERAL (SELECT * FROM facility f
                                             WHERE f.type = :type AND f.status IN ('ACTIVE', 'PLANNED')
                                             ORDER BY f.geom_m <-> b.geom_m LIMIT :k) f ON true
                             WHERE b.id = :id) t
                        ORDER BY distance_m LIMIT :limit""")
                .param("id", buildingId)
                .param("type", type)
                .param("k", limit * 3)
                .param("limit", limit)
                .query((rs, i) -> new NearbyFacility(rs.getInt("id"), rs.getString("type"), rs.getString("subtype"),
                        rs.getString("name"), rs.getString("status"), Math.round(rs.getDouble("distance_m") * 10) / 10.0,
                        rs.getDouble("lon"), rs.getDouble("lat")))
                .list();
    }

    private static BuildingSummary summary(ResultSet rs) throws SQLException {
        Integer builtYear = (Integer) rs.getObject("built_year");
        return new BuildingSummary(
                rs.getInt("id"), rs.getString("name"), rs.getString("use_type"), rs.getString("structure"),
                rs.getInt("floors"), rs.getDouble("height_m"), builtYear,
                builtYear == null ? null : Year.now().getValue() - builtYear,
                (Integer) rs.getObject("region_id"), rs.getString("region_name"),
                getDouble(rs, "risk_score"), rs.getString("risk_factors"),
                rs.getDouble("lon"), rs.getDouble("lat"));
    }

    private static Double getDouble(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).doubleValue();
    }
}
