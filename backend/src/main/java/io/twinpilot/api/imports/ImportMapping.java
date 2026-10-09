package io.twinpilot.api.imports;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * 원본 속성 → TwinPilot 속성 매핑.
 * <pre>
 * {
 *   "fields":   {"externalId": "원본 고유ID 필드", "name": "...", "useType": "...", "structure": "...",
 *                "floors": "...", "basementFloors": "...", "heightM": "...", "builtYear": "...",
 *                "subtype": "...", "status": "...", "installedYear": "...", "widthM": "...",
 *                "code": "...", "zoneType": "..."},
 *   "values":   {"useType": {"공동주택": "RESIDENTIAL_APT"}, "structure": {"철근콘크리트구조": "RC"}},
 *   "defaults": {"structure": "RC", "floors": 1},
 *   "floorHeightM": 3.0
 * }
 * </pre>
 * 필드를 지정하지 않으면 같은 이름(예: useType, use_type)의 속성을 찾는다. 원본 데이터셋의 항목 정의서를 보고 작성한다.
 */
public record ImportMapping(Map<String, String> fields, Map<String, Map<String, String>> values,
                            Map<String, Object> defaults, double floorHeightM) {

    public static final Set<String> TARGETS = Set.of("externalId", "name", "useType", "structure", "floors",
            "basementFloors", "heightM", "builtYear", "subtype", "status", "installedYear", "widthM", "code",
            "zoneType");

    public static ImportMapping from(JsonNode node) {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (node != null && !node.isNull() && !node.isMissingNode()) {
            node.path("fields").properties().forEach(e -> {
                if (!TARGETS.contains(e.getKey())) {
                    throw new IllegalArgumentException("알 수 없는 매핑 대상 필드: " + e.getKey());
                }
                fields.put(e.getKey(), e.getValue().asString());
            });
            node.path("values").properties().forEach(e -> {
                Map<String, String> m = new LinkedHashMap<>();
                e.getValue().properties().forEach(v -> m.put(v.getKey(), v.getValue().asString()));
                values.put(e.getKey(), m);
            });
            node.path("defaults").properties().forEach(e -> {
                JsonNode v = e.getValue();
                defaults.put(e.getKey(), v.isNumber() ? v.numberValue() : v.asString());
            });
        }
        double floorHeight = node == null ? 3.0 : node.path("floorHeightM").asDouble(3.0);
        if (floorHeight <= 0 || floorHeight > 10) {
            throw new IllegalArgumentException("floorHeightM은 0~10m여야 합니다.");
        }
        return new ImportMapping(fields, values, defaults, floorHeight);
    }

    /** 원본 속성에서 대상 값을 꺼낸다(필드 매핑 → 같은 이름/스네이크 표기 → 기본값 순). 값 매핑을 적용한다. */
    public String text(JsonNode props, String target) {
        JsonNode raw = null;
        String field = fields.get(target);
        if (field != null) {
            raw = props.get(field);
        } else {
            raw = props.get(target);
            if (raw == null) {
                raw = props.get(snake(target));
            }
        }
        String value = raw == null || raw.isNull() ? null : raw.asString().trim();
        if (value != null && value.isEmpty()) {
            value = null;
        }
        if (value != null && values.containsKey(target)) {
            value = values.get(target).getOrDefault(value, value);
        }
        if (value == null && defaults.containsKey(target)) {
            value = String.valueOf(defaults.get(target));
        }
        return value;
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
