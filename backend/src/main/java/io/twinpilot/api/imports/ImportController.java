package io.twinpilot.api.imports;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.job.JobQueue;
import io.twinpilot.api.job.JobView;
import io.twinpilot.api.model.storage.ObjectStorage;
import io.twinpilot.api.security.CurrentUser;

/** 외부 GeoJSON 데이터 적재(ADMIN). 파일을 저장소에 두고 IMPORT Job으로 처리한다. */
@RestController
@RequestMapping("/api/v1/admin/imports")
public class ImportController {

    private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final JobQueue jobs;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final CurrentUser currentUser;

    public ImportController(JobQueue jobs, ObjectStorage storage, ObjectMapper mapper, CurrentUser currentUser) {
        this.jobs = jobs;
        this.storage = storage;
        this.mapper = mapper;
        this.currentUser = currentUser;
    }

    /**
     * @param layer       BUILDING | HYDRANT | AED | FIRE_STATION | ROAD | REGION
     * @param source      데이터셋 이름(소문자·숫자·._-). 같은 출처끼리 외부 식별자로 갱신한다
     * @param srid        원본 좌표계 EPSG 코드(생략 시 파일의 crs 또는 4326)
     * @param mode        UPSERT(기본) | REPLACE(이 출처에서 파일에 없는 행 제거·비활성)
     * @param mapping     속성 매핑 JSON({@link ImportMapping})
     * @param dryRun      true면 검증 보고서만 만든다
     * @param refreshRisk 적재 후 출동 시간·위험도 재계산(기본 true)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobView> create(@RequestPart("file") MultipartFile file,
                                          @RequestParam String layer,
                                          @RequestParam String source,
                                          @RequestParam(required = false) Integer srid,
                                          @RequestParam(defaultValue = "UPSERT") String mode,
                                          @RequestParam(required = false) String mapping,
                                          @RequestParam(defaultValue = "false") boolean dryRun,
                                          @RequestParam(defaultValue = "true") boolean refreshRisk) {
        ImportLayer importLayer;
        try {
            importLayer = ImportLayer.valueOf(layer);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("layer는 " + List.of(ImportLayer.values()) + " 중 하나여야 합니다.");
        }
        if (!SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("source는 소문자·숫자·._- 1~64자여야 합니다.");
        }
        if (!mode.equals("UPSERT") && !mode.equals("REPLACE")) {
            throw new IllegalArgumentException("mode는 UPSERT 또는 REPLACE여야 합니다.");
        }
        if (importLayer == ImportLayer.REGION && mode.equals("REPLACE")) {
            throw new IllegalArgumentException("행정구역은 REPLACE 모드를 지원하지 않습니다.");
        }
        if (srid != null && !GeoJsonImportService.ALLOWED_SRIDS.contains(srid)) {
            throw new IllegalArgumentException("지원하지 않는 좌표계 EPSG:" + srid);
        }
        JsonNode mappingNode = mapper.createObjectNode();
        if (mapping != null && !mapping.isBlank()) {
            try {
                mappingNode = mapper.readTree(mapping);
            } catch (tools.jackson.core.JacksonException e) {
                throw new IllegalArgumentException("mapping이 올바른 JSON이 아닙니다: " + e.getOriginalMessage());
            }
            ImportMapping.from(mappingNode);   // 형식 오류는 요청 단계에서 400으로 돌려준다
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }
        String key = "imports/%s.geojson".formatted(UUID.randomUUID());
        try (InputStream in = file.getInputStream()) {
            storage.put(key, in, file.getSize(), "application/geo+json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("layer", importLayer.name());
        params.put("source", source);
        params.put("srid", srid);
        params.put("mode", mode);
        params.put("mapping", mappingNode);
        params.put("dryRun", dryRun);
        params.put("refreshRisk", refreshRisk);
        params.put("storageKey", key);
        params.put("filename", file.getOriginalFilename());
        params.put("sizeBytes", file.getSize());
        JobView job = jobs.submit(GeoJsonImportService.JOB_TYPE, params, currentUser.get(), null);
        return ResponseEntity.accepted().location(URI.create("/api/v1/analysis/jobs/" + job.id())).body(job);
    }

    @GetMapping
    public List<JobView> list(@RequestParam(defaultValue = "20") int limit) {
        return jobs.list(currentUser.get(), true, GeoJsonImportService.JOB_TYPE, limit);
    }
}
