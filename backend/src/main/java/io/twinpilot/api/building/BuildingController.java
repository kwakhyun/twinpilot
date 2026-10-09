package io.twinpilot.api.building;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.twinpilot.api.building.BuildingDtos.BuildingDetail;
import io.twinpilot.api.building.BuildingDtos.SearchResult;
import io.twinpilot.api.common.GeoJson;

@RestController
@RequestMapping("/api/v1/buildings")
public class BuildingController {

    private final BuildingRepository repository;
    private final BuildingService service;

    public BuildingController(BuildingRepository repository, BuildingService service) {
        this.repository = repository;
        this.service = service;
    }

    /** 3D 렌더링용 GeoJSON. bbox=minLon,minLat,maxLon,maxLat */
    @GetMapping
    public ResponseEntity<String> features(@RequestParam(required = false) Integer regionId,
                                           @RequestParam(required = false) String bbox,
                                           @RequestParam(required = false) Double minRisk,
                                           @RequestParam(required = false) Integer minAge,
                                           @RequestParam(required = false) List<String> useType,
                                           @RequestParam(required = false) Integer minFloors,
                                           @RequestParam(required = false) String uncoveredBy) {
        var filter = new BuildingFilter(regionId, parseBbox(bbox), minRisk, minAge, null, useType, minFloors,
                uncoveredBy, null);
        return GeoJson.ok(repository.featureCollection(filter));
    }

    /** 에이전트·표 형식용 검색(지오메트리 제외, 대표점 좌표 포함). */
    @GetMapping("/search")
    public SearchResult search(@RequestParam(required = false) Integer regionId,
                               @RequestParam(required = false) String bbox,
                               @RequestParam(required = false) Double minRisk,
                               @RequestParam(required = false) Integer minAge,
                               @RequestParam(required = false) Integer builtBefore,
                               @RequestParam(required = false) List<String> useType,
                               @RequestParam(required = false) Integer minFloors,
                               @RequestParam(required = false) String uncoveredBy,
                               @RequestParam(required = false) List<Integer> ids,
                               @RequestParam(defaultValue = "risk") String sort,
                               @RequestParam(defaultValue = "50") int limit) {
        var filter = new BuildingFilter(regionId, parseBbox(bbox), minRisk, minAge, builtBefore, useType, minFloors,
                uncoveredBy, ids);
        return service.search(filter, sort, limit);
    }

    @GetMapping("/{id}")
    public BuildingDetail detail(@PathVariable int id) {
        return service.detail(id);
    }

    static double[] parseBbox(String bbox) {
        if (bbox == null || bbox.isBlank()) {
            return null;
        }
        try {
            return Arrays.stream(bbox.split(",")).mapToDouble(s -> Double.parseDouble(s.trim())).toArray();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bbox 숫자 형식 오류: " + bbox);
        }
    }
}
