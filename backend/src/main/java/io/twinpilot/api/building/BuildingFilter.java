package io.twinpilot.api.building;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.twinpilot.api.common.RadiusPolicy;

/**
 * 건물 검색 조건. 파라미터 바인딩만 쓰는 WHERE 절을 만든다(SQL 인젝션 방지).
 *
 * @param uncoveredBy 지정하면 해당 시설(HYDRANT/AED/FIRE_STATION)의 기준 반경 밖 건물만 고른다
 */
public record BuildingFilter(Integer regionId, double[] bbox, Double minRisk, Integer minAge, Integer builtBefore,
                             List<String> useTypes, Integer minFloors, String uncoveredBy, List<Integer> ids) {

    public static BuildingFilter none() {
        return new BuildingFilter(null, null, null, null, null, null, null, null, null);
    }

    /** {@code b}=building, {@code r}=region alias 기준 WHERE 절. */
    public String where(Map<String, Object> p, RadiusPolicy radius) {
        List<String> c = new ArrayList<>();
        if (regionId != null) {
            c.add("b.region_id = :regionId");
            p.put("regionId", regionId);
        }
        if (bbox != null) {
            if (bbox.length != 4) {
                throw new IllegalArgumentException("bbox는 minLon,minLat,maxLon,maxLat 형식이어야 합니다.");
            }
            c.add("b.geom && ST_MakeEnvelope(:minx, :miny, :maxx, :maxy, 4326)");
            p.put("minx", bbox[0]);
            p.put("miny", bbox[1]);
            p.put("maxx", bbox[2]);
            p.put("maxy", bbox[3]);
        }
        if (minRisk != null) {
            c.add("b.risk_score >= :minRisk");
            p.put("minRisk", minRisk);
        }
        if (minAge != null) {
            c.add("b.built_year <= :maxBuiltYear");
            p.put("maxBuiltYear", Year.now().getValue() - minAge);
        }
        if (builtBefore != null) {
            c.add("b.built_year < :builtBefore");
            p.put("builtBefore", builtBefore);
        }
        if (useTypes != null && !useTypes.isEmpty()) {
            c.add("b.use_type IN (:useTypes)");
            p.put("useTypes", useTypes);
        }
        if (minFloors != null) {
            c.add("b.floors >= :minFloors");
            p.put("minFloors", minFloors);
        }
        if (ids != null && !ids.isEmpty()) {
            c.add("b.id IN (:ids)");
            p.put("ids", ids);
        }
        if (uncoveredBy != null) {
            radius.bind(p, uncoveredBy, null, false);
            c.add("""
                    NOT EXISTS (SELECT 1 FROM facility f
                                 WHERE f.type = :ftype AND f.status IN (:statuses)
                                   AND ST_DWithin(f.geom_m, b.geom_m, %s))""".formatted(radius.sqlExpr("r.zone_type")));
        }
        return c.isEmpty() ? "" : "WHERE " + String.join(" AND ", c);
    }
}
