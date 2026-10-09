package io.twinpilot.api.building;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonRawValue;

import io.twinpilot.api.sensor.SensorReading;

public final class BuildingDtos {

    private BuildingDtos() {
    }

    public record BuildingSummary(int id, String name, String useType, String structure, int floors, double heightM,
                                  Integer builtYear, Integer age, Integer regionId, String regionName, Double risk,
                                  @JsonRawValue String riskFactors, double lon, double lat) {
    }

    public record SearchResult(long total, Double avgRisk, Double maxRisk, int returned, String sort,
                               List<BuildingSummary> items) {
    }

    public record NearbyFacility(int id, String type, String subtype, String name, String status, double distanceM,
                                 double lon, double lat) {
    }

    /** 사다리차 도달 분석: 층고를 균등하다고 보고 유효 높이를 넘는 층을 계산한다. */
    public record LadderAnalysis(double ladderReachM, double floorHeightM, int reachableFloor, int floorsBeyondReach) {
    }

    /**
     * 출동 여건. estimatedMinutes는 도로망 최단경로 시간(있으면) 또는 직선거리 근사.
     * straightLineMinutes는 비교용 직선 근사, roadAccessM은 가장 가까운 도로까지 거리(소방차 접근성).
     */
    public record ResponseEstimate(String stationName, double straightDistanceM, double estimatedMinutes,
                                   double targetMinutes, boolean withinTarget, String method,
                                   double straightLineMinutes, Double roadAccessM) {
    }

    public record BuildingDetail(BuildingSummary building, int basementFloors, @JsonRawValue String footprint,
                                 List<NearbyFacility> nearestHydrants, List<NearbyFacility> nearestAeds,
                                 ResponseEstimate response, LadderAnalysis ladder,
                                 List<SensorReading> hydrantSensors, long queryMillis) {
    }
}
