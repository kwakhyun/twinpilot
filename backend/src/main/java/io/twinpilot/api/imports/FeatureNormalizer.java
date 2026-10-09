package io.twinpilot.api.imports;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/** 원본 피처 속성을 레이어별 표준 속성으로 바꾸고 검증한다. 오류는 IllegalArgumentException으로 알린다. */
final class FeatureNormalizer {

    static final Set<String> USE_TYPES = Set.of("RESIDENTIAL_APT", "RESIDENTIAL_HOUSE", "COMMERCIAL", "OFFICE",
            "PUBLIC", "SCHOOL", "INDUSTRIAL", "WAREHOUSE", "OTHER");
    static final Set<String> STRUCTURES = Set.of("RC", "STEEL", "MASONRY", "WOOD");
    static final Set<String> ZONES = Set.of("RESIDENTIAL", "COMMERCIAL", "INDUSTRIAL", "GREEN", "OTHER");
    static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Pattern YEAR = Pattern.compile("(1[89]\\d{2}|20\\d{2})");

    private final ImportLayer layer;
    private final ImportMapping mapping;
    private final boolean requireExternalId;

    FeatureNormalizer(ImportLayer layer, ImportMapping mapping, boolean requireExternalId) {
        this.layer = layer;
        this.mapping = mapping;
        this.requireExternalId = requireExternalId;
    }

    record Normalized(String externalId, Map<String, Object> props, String geometryJson) {
    }

    Normalized normalize(JsonNode feature) {
        JsonNode geometry = feature.path("geometry");
        if (geometry.isMissingNode() || geometry.isNull()) {
            throw new IllegalArgumentException("지오메트리가 없습니다.");
        }
        String type = geometry.path("type").asString();
        if (!layer.geometryTypes().contains(type)) {
            throw new IllegalArgumentException("%s 레이어는 %s 지오메트리만 받습니다(입력: %s)."
                    .formatted(layer, layer.geometryTypes(), type));
        }
        if (!geometry.path("coordinates").isArray() || geometry.path("coordinates").isEmpty()) {
            throw new IllegalArgumentException("좌표가 비어 있습니다.");
        }
        JsonNode props = feature.path("properties");
        String externalId = mapping.text(props, "externalId");
        if (externalId == null && feature.has("id") && !feature.path("id").isNull()) {
            externalId = feature.path("id").asString();
        }
        if (externalId != null && externalId.length() > 128) {
            throw new IllegalArgumentException("외부 식별자는 128자 이하여야 합니다.");
        }
        if (externalId == null && requireExternalId) {
            throw new IllegalArgumentException("REPLACE 모드는 모든 피처에 외부 식별자(externalId)가 필요합니다.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        switch (layer) {
            case BUILDING -> building(props, out);
            case HYDRANT, AED, FIRE_STATION -> facility(props, out);
            case ROAD -> road(props, out);
            case REGION -> region(props, out, externalId);
        }
        return new Normalized(externalId, out, geometry.toString());
    }

    private void building(JsonNode p, Map<String, Object> out) {
        out.put("name", limit(mapping.text(p, "name"), 128));
        String use = upper(mapping.text(p, "useType"));
        if (use == null || !USE_TYPES.contains(use)) {
            throw new IllegalArgumentException("용도(useType) 값이 없거나 알 수 없습니다: %s (허용: %s, 값 매핑으로 변환하세요)"
                    .formatted(use, USE_TYPES));
        }
        out.put("useType", use);
        String structure = upper(mapping.text(p, "structure"));
        if (structure == null || !STRUCTURES.contains(structure)) {
            throw new IllegalArgumentException("구조(structure) 값이 없거나 알 수 없습니다: %s (허용: %s)"
                    .formatted(structure, STRUCTURES));
        }
        out.put("structure", structure);
        int floors = integer(mapping.text(p, "floors"), "floors", 0, 200, null);
        out.put("floors", floors);
        out.put("basementFloors", integer(mapping.text(p, "basementFloors"), "basementFloors", 0, 30, 0));
        Double height = decimal(mapping.text(p, "heightM"), "heightM", 0, 1000);
        out.put("heightM", height != null && height > 0 ? height : Math.max(floors, 1) * mapping.floorHeightM());
        out.put("builtYear", year(mapping.text(p, "builtYear")));
    }

    private void facility(JsonNode p, Map<String, Object> out) {
        out.put("name", limit(mapping.text(p, "name"), 128));
        out.put("subtype", limit(mapping.text(p, "subtype"), 32));
        String status = upper(mapping.text(p, "status"));
        if (status == null) {
            status = "ACTIVE";
        }
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("상태(status)는 ACTIVE/INACTIVE여야 합니다: " + status);
        }
        out.put("status", status);
        out.put("installedYear", year(mapping.text(p, "installedYear")));
    }

    private void road(JsonNode p, Map<String, Object> out) {
        out.put("name", limit(mapping.text(p, "name"), 64));
        Double width = decimal(mapping.text(p, "widthM"), "widthM", 0.5, 200);
        out.put("widthM", width == null ? 10.0 : width);
    }

    private void region(JsonNode p, Map<String, Object> out, String externalId) {
        String code = mapping.text(p, "code");
        if (code == null) {
            code = externalId;
        }
        String name = mapping.text(p, "name");
        if (code == null || name == null) {
            throw new IllegalArgumentException("행정구역은 code와 name이 필요합니다.");
        }
        String zone = upper(mapping.text(p, "zoneType"));
        if (zone == null || !ZONES.contains(zone)) {
            throw new IllegalArgumentException("용도지역(zoneType)이 없거나 알 수 없습니다: %s (허용: %s)".formatted(zone, ZONES));
        }
        out.put("code", limit(code, 32));
        out.put("name", limit(name, 64));
        out.put("zoneType", zone);
    }

    private static String upper(String v) {
        return v == null ? null : v.trim().toUpperCase();
    }

    private static String limit(String v, int max) {
        return v == null ? null : v.length() <= max ? v : v.substring(0, max);
    }

    private static Integer integer(String v, String field, int min, int max, Integer fallback) {
        if (v == null) {
            if (fallback == null) {
                throw new IllegalArgumentException(field + " 값이 없습니다.");
            }
            return fallback;
        }
        try {
            int n = (int) Math.round(Double.parseDouble(v));
            if (n < min || n > max) {
                throw new IllegalArgumentException("%s 값이 범위(%d~%d)를 벗어났습니다: %s".formatted(field, min, max, v));
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 값이 숫자가 아닙니다: " + v);
        }
    }

    private static Double decimal(String v, String field, double min, double max) {
        if (v == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            if (d < min || d > max) {
                throw new IllegalArgumentException("%s 값이 범위(%s~%s)를 벗어났습니다: %s".formatted(field, min, max, v));
            }
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 값이 숫자가 아닙니다: " + v);
        }
    }

    /** "1995", "19950312", "1995-03-12" 같은 값에서 연도를 꺼낸다. */
    static Integer year(String v) {
        if (v == null) {
            return null;
        }
        Matcher m = YEAR.matcher(v);
        if (!m.find()) {
            throw new IllegalArgumentException("연도를 해석할 수 없습니다: " + v);
        }
        int y = Integer.parseInt(m.group(1));
        if (y > Year.now().getValue() + 1) {
            throw new IllegalArgumentException("미래 연도입니다: " + v);
        }
        return y;
    }
}
