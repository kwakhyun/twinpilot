package io.twinpilot.api.common;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** PostGIS가 만든 GeoJSON 문자열을 그대로 내려보낸다(Java 쪽 파싱·재직렬화 비용 제거). */
public final class GeoJson {

    public static final MediaType MEDIA_TYPE = MediaType.parseMediaType("application/geo+json");

    private GeoJson() {
    }

    public static ResponseEntity<String> ok(String featureCollection) {
        return ResponseEntity.ok().contentType(MEDIA_TYPE).body(featureCollection);
    }

    /** {@code featureExpr}는 행마다 GeoJSON Feature(json) 하나를 만드는 식이다. */
    public static String collect(String featureExpr, String fromWhere, String orderBy) {
        return """
                SELECT json_build_object('type', 'FeatureCollection',
                                         'features', coalesce(json_agg(%s ORDER BY %s), '[]'::json))::text
                %s""".formatted(featureExpr, orderBy, fromWhere);
    }
}
