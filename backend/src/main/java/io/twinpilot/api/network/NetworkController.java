package io.twinpilot.api.network;

import java.util.Arrays;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.twinpilot.api.common.GeoJson;

@RestController
@RequestMapping("/api/v1/analysis")
public class NetworkController {

    private final RoadNetworkService network;
    private final ResponseAnalysisService response;

    public NetworkController(RoadNetworkService network, ResponseAnalysisService response) {
        this.network = network;
        this.response = response;
    }

    public record ResponseRequest(Integer regionId, @DecimalMin("1") @DecimalMax("60") Double targetMin,
                                  @Min(0) @Max(200) Integer top) {
    }

    /** 도로망 출동 시간 분석(동기). 건물별 시간은 REFRESH_RISK Job이 미리 계산해 둔다. */
    @PostMapping("/response")
    public ResponseAnalysisService.ResponseResult response(@Valid @RequestBody ResponseRequest req) {
        return response.analyze(req.regionId(), req.targetMin(), req.top() == null ? 20 : req.top());
    }

    /** 출동 도달권(GeoJSON). minutes=3,5,7 처럼 최대 5개, 각 1~30분. */
    @GetMapping("/isochrones")
    public ResponseEntity<String> isochrones(@RequestParam(defaultValue = "3,5,7") String minutes,
                                             @RequestParam(required = false) Integer stationId,
                                             @RequestParam(defaultValue = "60") double bufferM) {
        List<Double> parsed;
        try {
            parsed = Arrays.stream(minutes.split(",")).map(String::trim).map(Double::parseDouble).sorted().toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("minutes는 쉼표로 구분한 숫자여야 합니다: " + minutes);
        }
        if (parsed.isEmpty() || parsed.size() > 5 || parsed.getFirst() < 1 || parsed.getLast() > 30) {
            throw new IllegalArgumentException("minutes는 1~30분 사이 값 1~5개여야 합니다.");
        }
        if (bufferM < 10 || bufferM > 300) {
            throw new IllegalArgumentException("bufferM은 10~300m여야 합니다.");
        }
        return GeoJson.ok(network.isochrones(parsed, stationId, bufferM));
    }

    @GetMapping("/network")
    public ResponseEntity<RoadNetworkService.NetworkState> state() {
        return ResponseEntity.of(network.state());
    }
}
