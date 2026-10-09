package io.twinpilot.api.analysis;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonRawValue;

public final class AnalysisDtos {

    private AnalysisDtos() {
    }

    public record CoverageRequest(
            @NotBlank @Pattern(regexp = "HYDRANT|AED|FIRE_STATION") String facilityType,
            Double radiusM,
            Integer regionId,
            Boolean includePlanned,
            Boolean includeBlindSpots,
            @Min(0) @Max(200) Integer topUncovered) {

        public boolean planned() {
            return Boolean.TRUE.equals(includePlanned);
        }

        public boolean blindSpots() {
            return includeBlindSpots == null || includeBlindSpots;
        }

        public int top() {
            return topUncovered == null ? 20 : topUncovered;
        }
    }

    public record RegionCoverage(int regionId, String regionName, String zoneType, double radiusM, int totalBuildings,
                                 int coveredBuildings, double coverageRate, int uncoveredHighRisk,
                                 double uncoveredRiskSum) {
    }

    public record UncoveredBuilding(int id, String name, String useType, Integer builtYear, int floors, Double risk,
                                    Integer regionId, double nearestFacilityM, double lon, double lat) {
    }

    public record BlindSpot(int regionId, double areaM2, @JsonRawValue String geometry) {
    }

    public record CoverageSummary(int totalBuildings, int coveredBuildings, double coverageRate, int uncoveredHighRisk,
                                  double blindSpotAreaM2) {
    }

    public record CoverageResult(String facilityType, boolean includePlanned, Double radiusOverrideM,
                                 CoverageSummary summary, List<RegionCoverage> regions,
                                 List<Integer> uncoveredBuildingIds, List<UncoveredBuilding> topUncovered,
                                 List<BlindSpot> blindSpots, String standard, long elapsedMs) {
    }

    public record JobRequest(
            @NotBlank @Pattern(regexp = "RECOMMEND_SITES|REFRESH_RISK") String type,
            @Pattern(regexp = "HYDRANT|AED") String facilityType,
            @Min(1) @Max(20) Integer count,
            Double radiusM,
            Integer regionId,
            @Min(10) @Max(200) Integer cellSizeM) {
    }

    public record Site(int rank, double lon, double lat, Integer regionId, String regionName, double radiusM,
                       int newlyCoveredBuildings, double riskCovered, List<Integer> buildingIds) {
    }

    public record RecommendationResult(String facilityType, int requested, List<Site> sites, int candidateCount,
                                       int totalBuildings, double coverageBefore, double coverageAfter,
                                       int newlyCovered, double riskCovered, String method, long elapsedMs) {
    }
}
