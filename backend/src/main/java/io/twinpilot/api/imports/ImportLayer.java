package io.twinpilot.api.imports;

import java.util.Set;

/** 적재 대상 레이어와 허용 지오메트리 유형. */
public enum ImportLayer {
    BUILDING(Set.of("Polygon", "MultiPolygon")),
    HYDRANT(Set.of("Point")),
    AED(Set.of("Point")),
    FIRE_STATION(Set.of("Point")),
    ROAD(Set.of("LineString", "MultiLineString")),
    REGION(Set.of("Polygon", "MultiPolygon"));

    private final Set<String> geometryTypes;

    ImportLayer(Set<String> geometryTypes) {
        this.geometryTypes = geometryTypes;
    }

    public Set<String> geometryTypes() {
        return geometryTypes;
    }

    public boolean facility() {
        return this == HYDRANT || this == AED || this == FIRE_STATION;
    }
}
