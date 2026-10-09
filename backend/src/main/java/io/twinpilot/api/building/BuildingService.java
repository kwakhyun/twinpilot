package io.twinpilot.api.building;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.twinpilot.api.building.BuildingDtos.BuildingDetail;
import io.twinpilot.api.building.BuildingDtos.LadderAnalysis;
import io.twinpilot.api.building.BuildingDtos.NearbyFacility;
import io.twinpilot.api.building.BuildingDtos.ResponseEstimate;
import io.twinpilot.api.building.BuildingDtos.SearchResult;
import io.twinpilot.api.config.TwinPilotProperties;
import io.twinpilot.api.sensor.SensorReading;
import io.twinpilot.api.sensor.SensorHub;

@Service
public class BuildingService {

    private final BuildingRepository repository;
    private final SensorHub sensors;
    private final ExecutorService executor;
    private final TwinPilotProperties.Standards standards;

    public BuildingService(BuildingRepository repository, SensorHub sensors, ExecutorService virtualThreadExecutor,
                           TwinPilotProperties props) {
        this.repository = repository;
        this.sensors = sensors;
        this.executor = virtualThreadExecutor;
        this.standards = props.standards();
    }

    public SearchResult search(BuildingFilter filter, String sort, int limit) {
        var aggregate = async(() -> repository.aggregate(filter));
        var items = async(() -> repository.search(filter, sort, limit));
        var agg = join(aggregate);
        var list = join(items);
        return new SearchResult(agg.total(), agg.avgRisk(), agg.maxRisk(), list.size(), sort, list);
    }

    /**
     * 건물 상세: 기본 정보, 최근접 소화전·AED·소방서 조회를 가상 스레드에서 동시에 실행한다.
     * 순차 실행 대비 지연 시간이 가장 느린 쿼리 하나 수준으로 줄어든다.
     */
    public BuildingDetail detail(int id) {
        long started = System.nanoTime();
        var base = async(() -> repository.findById(id));
        var hydrants = async(() -> repository.nearest(id, "HYDRANT", 3));
        var aeds = async(() -> repository.nearest(id, "AED", 3));
        var stations = async(() -> repository.nearest(id, "FIRE_STATION", 1));
        CompletableFuture.allOf(base, hydrants, aeds, stations).exceptionally(e -> null).join();

        var row = join(base).orElseThrow(() -> new NoSuchElementException("건물을 찾을 수 없습니다: " + id));
        List<NearbyFacility> nearHydrants = join(hydrants);
        Set<Integer> hydrantIds = nearHydrants.stream().map(NearbyFacility::id).collect(Collectors.toSet());
        List<SensorReading> hydrantSensors = sensors.snapshot().stream()
                .filter(s -> s.facilityId() != null && hydrantIds.contains(s.facilityId()))
                .sorted(Comparator.comparing(SensorReading::sensorId))
                .toList();

        ResponseEstimate response = join(stations).stream().findFirst()
                .map(s -> {
                    double minutes = Math.round(standards.responseMinutes(s.distanceM()) * 10) / 10.0;
                    return new ResponseEstimate(s.name(), s.distanceM(), minutes, standards.responseTargetMin(),
                            minutes <= standards.responseTargetMin(),
                            "직선거리×%.1f÷%.0fkm/h 근사".formatted(standards.detourFactor(), standards.travelSpeedKmh()));
                })
                .orElse(null);

        var b = row.summary();
        double floorHeight = b.floors() > 0 ? b.heightM() / b.floors() : 3.0;
        int reachable = (int) Math.floor(standards.ladderReachM() / floorHeight);
        LadderAnalysis ladder = new LadderAnalysis(standards.ladderReachM(), Math.round(floorHeight * 100) / 100.0,
                Math.min(reachable, b.floors()), Math.max(0, b.floors() - reachable));

        return new BuildingDetail(b, row.basementFloors(), row.footprint(), nearHydrants, join(aeds), response, ladder,
                hydrantSensors, (System.nanoTime() - started) / 1_000_000);
    }

    private <T> CompletableFuture<T> async(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }
}
