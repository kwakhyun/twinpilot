package io.twinpilot.api.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("twinpilot")
public record TwinPilotProperties(Seed seed, Cors cors, Storage storage, Standards standards, Sensors sensors,
                                  Security security, Jobs jobs, Network network, Imports imports) {

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

    /**
     * @param simulate      데모용 센서 시뮬레이터 실행 여부(레플리카 중 리더 하나만 생성한다)
     * @param retentionDays 측정 이력 보존 일수(일 단위 파티션을 통째로 지운다)
     * @param maxBatch      수집 API 한 번에 받는 최대 측정값 수
     */
    public record Sensors(boolean simulate, long intervalMs, double lowPressureMpa, int retentionDays, int maxBatch) {
    }

    /**
     * @param mode       oidc | disabled(로컬 개발 전용)
     * @param roleClaims 역할을 읽을 JWT 클레임 경로(점 구분). 앞에서부터 모두 합친다.
     */
    public record Security(String mode, List<String> roleClaims) {

        public boolean disabled() {
            return "disabled".equalsIgnoreCase(mode);
        }
    }

    /**
     * DB 기반 작업 큐.
     * @param concurrency      레플리카당 동시 실행 Job 수
     * @param lease            실행 임대 기간. 하트비트로 연장하며, 만료되면 다른 레플리카가 다시 가져간다.
     * @param maxAttempts      임대 만료(프로세스 중단)로 인한 최대 재시도 횟수
     * @param maxActivePerUser 사용자별 대기·실행 중 Job 한도(초과 시 429)
     * @param retention        끝난 Job 기록 보존 기간
     * @param pollInterval     알림을 놓쳤을 때를 대비한 큐 확인 주기
     */
    public record Jobs(int concurrency, Duration lease, int maxAttempts, int maxActivePerUser, Duration retention,
                       Duration pollInterval) {
    }

    /**
     * 도로망 기반 출동 시간 계산 기준(가정값).
     * @param wideRoadKmh   폭 20m 이상 도로 주행속도
     * @param mediumRoadKmh 폭 12m 이상
     * @param narrowRoadKmh 그 외
     * @param accessKmh     도로에서 건물·소방서까지 접근 구간 속도
     * @param turnoutSec    출동 준비 시간(신고 접수~차고 출발)
     */
    public record Network(double wideRoadKmh, double mediumRoadKmh, double narrowRoadKmh, double accessKmh,
                          double turnoutSec) {
    }

    /**
     * @param maxFeatures 파일 하나에서 받는 최대 피처 수
     * @param batchSize   스테이징 적재 배치 크기
     */
    public record Imports(int maxFeatures, int batchSize) {
    }
}
