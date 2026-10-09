package io.twinpilot.api.sensor;

import java.time.Instant;
import java.util.Map;

/**
 * 센서 최신값. HYDRANT_PRESSURE는 pressureMpa, AIR_QUALITY는 pm25·pm10·vocPpb 값을 가진다.
 * status: NORMAL/ALERT(수압), GOOD/NORMAL/BAD/VERY_BAD(미세먼지 PM2.5 국내 예보 등급 기준)
 */
public record SensorReading(int sensorId, String code, String kind, Integer facilityId, Integer routeId,
                            double lon, double lat, Map<String, Double> values, String status, Instant ts) {
}
