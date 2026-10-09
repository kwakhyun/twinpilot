package io.twinpilot.api.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("twinpilot")
public record TwinPilotProperties(Seed seed, Cors cors, Storage storage, Standards standards, Sensors sensors) {

    public record Seed(boolean enabled) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Storage(String type, String localRoot, S3 s3) {

        public record S3(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        }
    }

    /**
     * 분석 기준값. 법정 기준(소화전 수평거리)과 가정값(AED 반경, 주행속도 등)을 설정으로 분리한다.
     */
    public record Standards(double hydrantUrbanM, double hydrantOtherM, double aedM, double responseTargetMin,
                            double travelSpeedKmh, double detourFactor, double ladderReachM,
                            double highRiskThreshold) {

        public double speedMetersPerMin() {
            return travelSpeedKmh * 1000 / 60;
        }

        /** 골든타임 안에 도달 가능한 소방서 직선 반경. */
        public double stationRadiusM() {
            return responseTargetMin * speedMetersPerMin() / detourFactor;
        }

        public double responseMinutes(double straightDistanceM) {
            return straightDistanceM * detourFactor / speedMetersPerMin();
        }
    }

    public record Sensors(boolean simulate, long intervalMs, long persistIntervalMs, double lowPressureMpa) {
    }
}
