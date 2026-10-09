package io.twinpilot.api.common;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.twinpilot.api.config.TwinPilotProperties;

/**
 * 시설 유형·용도지역별 커버 반경을 SQL 식으로 만든다.
 * 반경 직접 지정(:radius)이 없으면 법정 기준(소화전)과 설정값(AED, 소방서 골든타임)을 쓴다.
 */
@Component
public class RadiusPolicy {

    public static final List<String> TYPES = List.of("HYDRANT", "AED", "FIRE_STATION");

    private final TwinPilotProperties.Standards standards;

    public RadiusPolicy(TwinPilotProperties props) {
        this.standards = props.standards();
    }

    /** {@code zoneColumn}은 region.zone_type 컬럼 식이다. 쿼리에 :ftype, :radius 등 파라미터가 필요하다. */
    public String sqlExpr(String zoneColumn) {
        return """
                (CASE WHEN CAST(:radius AS double precision) IS NOT NULL THEN CAST(:radius AS double precision)
                      WHEN :ftype = 'HYDRANT' AND %1$s IN ('RESIDENTIAL', 'COMMERCIAL', 'INDUSTRIAL') THEN :hydrantUrban
                      WHEN :ftype = 'HYDRANT' THEN :hydrantOther
                      WHEN :ftype = 'AED' THEN :aed
                      ELSE :stationRadius END)""".formatted(zoneColumn);
    }

    public void bind(Map<String, Object> params, String facilityType, Double radiusOverride, boolean includePlanned) {
        if (!TYPES.contains(facilityType)) {
            throw new IllegalArgumentException("facilityType은 " + TYPES + " 중 하나여야 합니다: " + facilityType);
        }
        if (radiusOverride != null && (radiusOverride <= 0 || radiusOverride > 10_000)) {
            throw new IllegalArgumentException("radiusM은 0보다 크고 10000 이하여야 합니다.");
        }
        params.put("ftype", facilityType);
        params.put("radius", radiusOverride);
        params.put("hydrantUrban", standards.hydrantUrbanM());
        params.put("hydrantOther", standards.hydrantOtherM());
        params.put("aed", standards.aedM());
        params.put("stationRadius", standards.stationRadiusM());
        params.put("statuses", includePlanned ? List.of("ACTIVE", "PLANNED") : List.of("ACTIVE"));
    }

    public TwinPilotProperties.Standards standards() {
        return standards;
    }
}
