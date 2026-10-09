package io.twinpilot.api.model;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.twinpilot.api.audit.AuditService;
import io.twinpilot.api.model.ModelAssetService.ModelAsset;
import io.twinpilot.api.model.ModelAssetService.Placement;
import io.twinpilot.api.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/models")
public class ModelAssetController {

    private final ModelAssetService service;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public ModelAssetController(ModelAssetService service, AuditService audit, CurrentUser currentUser) {
        this.service = service;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /** 업로드 후 즉시 202. 검증·메타 추출은 비동기로 진행되며 status로 확인한다. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ModelAsset> upload(@RequestPart("file") MultipartFile file,
                                             @RequestParam(required = false) String name,
                                             @RequestParam(required = false) Double lon,
                                             @RequestParam(required = false) Double lat,
                                             @RequestParam(defaultValue = "0") double heightM,
                                             @RequestParam(defaultValue = "0") double headingDeg,
                                             @RequestParam(defaultValue = "1") double scale) {
        Placement placement = lon != null && lat != null ? new Placement(lon, lat, heightM, headingDeg, scale) : null;
        if (name != null && name.length() > 128) {
            throw new IllegalArgumentException("이름은 128자 이하여야 합니다.");
        }
        ModelAsset asset = service.upload(name, file, placement, currentUser.get());
        audit.record(currentUser.get(), "MODEL_UPLOAD", "model", asset.id().toString(),
                java.util.Map.of("name", asset.name(), "sizeBytes", file.getSize()));
        return ResponseEntity.accepted().location(URI.create("/api/v1/models/" + asset.id())).body(asset);
    }

    @GetMapping
    public List<ModelAsset> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ModelAsset get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<InputStreamResource> file(@PathVariable UUID id) {
        ModelAsset m = service.get(id);
        MediaType type = "glb".equals(m.format()) ? MediaType.parseMediaType("model/gltf-binary")
                : MediaType.parseMediaType("model/gltf+json");
        String etag = service.etag(id);
        var builder = ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noCache().cachePrivate());
        if (etag != null) {
            builder.eTag(etag);
        }
        return builder.body(new InputStreamResource(service.open(id)));
    }

    public record PlacementRequest(@DecimalMin("-180") @DecimalMax("180") double lon,
                                   @DecimalMin("-90") @DecimalMax("90") double lat,
                                   @DecimalMin("-500") @DecimalMax("10000") double heightM,
                                   @DecimalMin("-360") @DecimalMax("360") double headingDeg,
                                   @Positive @DecimalMax("1000") Double scale) {
    }

    @PutMapping("/{id}/placement")
    public ModelAsset place(@PathVariable UUID id, @Valid @RequestBody PlacementRequest req) {
        ModelAsset placed = service.place(id, new Placement(req.lon(), req.lat(), req.heightM(), req.headingDeg(),
                req.scale() == null ? 1 : req.scale()));
        audit.record(currentUser.get(), "MODEL_PLACE", "model", id.toString(),
                java.util.Map.of("lon", req.lon(), "lat", req.lat(), "headingDeg", req.headingDeg()));
        return placed;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        audit.record(currentUser.get(), "MODEL_DELETE", "model", id.toString(), java.util.Map.of());
        return ResponseEntity.noContent().build();
    }
}
