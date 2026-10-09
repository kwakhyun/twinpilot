package io.twinpilot.api.layer;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.twinpilot.api.audit.AuditService;
import io.twinpilot.api.common.DataChangeEvents;
import io.twinpilot.api.common.GeoJson;
import io.twinpilot.api.config.TwinPilotProperties;
import io.twinpilot.api.security.CurrentUser;

/** 행정동·도로·시설·버스노선 레이어와 의사결정용 계획 시설(PLANNED) 관리. */
@RestController
@RequestMapping("/api/v1")
public class LayerController {

    private final JdbcClient jdbc;
    private final TwinPilotProperties.Standards standards;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final DataChangeEvents changes;
    private final TransactionTemplate tx;

    public LayerController(JdbcClient jdbc, TwinPilotProperties props, AuditService audit, CurrentUser currentUser,
                           DataChangeEvents changes, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.standards = props.standards();
        this.audit = audit;
        this.currentUser = currentUser;
        this.changes = changes;
        this.tx = tx;
    }

    @GetMapping("/regions")
    public ResponseEntity<String> regions() {
        String sql = GeoJson.collect("""
                json_build_object('type', 'Feature', 'id', r.id,
                    'geometry', ST_AsGeoJSON(r.geom, 7)::json,
                    'properties', json_build_object('code', r.code, 'name', r.name, 'zoneType', r.zone_type,
                        'hydrantRadiusM', CASE WHEN r.zone_type IN ('RESIDENTIAL', 'COMMERCIAL', 'INDUSTRIAL')
                                               THEN :urban ELSE :other END,
                        'areaM2', round(ST_Area(r.geom_m)::numeric),
                        'buildingCount', (SELECT count(*) FROM building b WHERE b.region_id = r.id)))""",
                "FROM region r", "r.id");
        return GeoJson.ok(jdbc.sql(sql)
                .param("urban", standards.hydrantUrbanM())
                .param("other", standards.hydrantOtherM())
                .query(String.class).single());
    }

    @GetMapping("/roads")
    public ResponseEntity<String> roads() {
        String sql = GeoJson.collect("""
                json_build_object('type', 'Feature', 'id', r.id, 'geometry', ST_AsGeoJSON(r.geom, 7)::json,
                    'properties', json_build_object('name', r.name, 'widthM', r.width_m))""",
                "FROM road r", "r.id");
        return GeoJson.ok(jdbc.sql(sql).query(String.class).single());
    }

    @GetMapping("/bus-routes")
    public ResponseEntity<String> busRoutes() {
        String sql = GeoJson.collect("""
                json_build_object('type', 'Feature', 'id', r.id, 'geometry', ST_AsGeoJSON(r.geom, 7)::json,
                    'properties', json_build_object('name', r.name))""",
                "FROM bus_route r", "r.id");
        return GeoJson.ok(jdbc.sql(sql).query(String.class).single());
    }

    @GetMapping("/facilities")
    public ResponseEntity<String> facilities(@RequestParam(required = false) String type,
                                             @RequestParam(required = false) List<String> status) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder where = new StringBuilder("FROM facility f WHERE true");
        if (type != null) {
            where.append(" AND f.type = :type");
            p.put("type", type);
        }
        if (status != null && !status.isEmpty()) {
            where.append(" AND f.status IN (:status)");
            p.put("status", status);
        }
        String sql = GeoJson.collect("""
                json_build_object('type', 'Feature', 'id', f.id, 'geometry', ST_AsGeoJSON(f.geom, 7)::json,
                    'properties', json_build_object('type', f.type, 'subtype', f.subtype, 'name', f.name,
                        'status', f.status, 'installedYear', f.installed_year, 'props', f.properties))""",
                where.toString(), "f.id");
        return GeoJson.ok(jdbc.sql(sql).params(p).query(String.class).single());
    }

    public record PlannedFacilityRequest(
            @NotBlank @Pattern(regexp = "HYDRANT|AED") String type,
            @Size(max = 64) String subtype,
            @Size(max = 128) String name,
            @DecimalMin("-180") @DecimalMax("180") double lon,
            @DecimalMin("-90") @DecimalMax("90") double lat) {
    }

    public record FacilityCreated(int id, String type, String status, double lon, double lat, Integer regionId) {
    }

    /** 추천 결과를 '계획(PLANNED)' 시설로 배치해 what-if 커버리지를 비교할 수 있게 한다. */
    @PostMapping("/facilities")
    public ResponseEntity<FacilityCreated> createPlanned(@Valid @RequestBody PlannedFacilityRequest req) {
        // 시설과 감사 기록을 한 트랜잭션으로 남긴다.
        FacilityCreated created = tx.execute(status -> insertPlanned(req));
        changes.changed("facility");
        return ResponseEntity.created(URI.create("/api/v1/facilities/" + created.id())).body(created);
    }

    private FacilityCreated insertPlanned(PlannedFacilityRequest req) {
        FacilityCreated created = jdbc.sql("""
                        INSERT INTO facility (type, subtype, name, status, installed_year, geom)
                        VALUES (:type, :subtype, :name, 'PLANNED', NULL, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326))
                        RETURNING id, type, status, ST_X(geom) AS lon, ST_Y(geom) AS lat,
                                  (SELECT r.id FROM region r WHERE ST_Contains(r.geom, facility.geom) LIMIT 1) AS region_id""")
                .param("type", req.type())
                .param("subtype", req.subtype() == null ? "PLANNED" : req.subtype())
                .param("name", req.name() == null ? "계획 " + req.type() : req.name())
                .param("lon", req.lon())
                .param("lat", req.lat())
                .query((rs, i) -> new FacilityCreated(rs.getInt("id"), rs.getString("type"), rs.getString("status"),
                        rs.getDouble("lon"), rs.getDouble("lat"), (Integer) rs.getObject("region_id")))
                .single();
        audit.record(currentUser.get(), "FACILITY_PLAN", "facility", Integer.toString(created.id()),
                Map.of("type", created.type(), "lon", created.lon(), "lat", created.lat()));
        return created;
    }

    @DeleteMapping("/facilities/{id}")
    public ResponseEntity<Void> deletePlanned(@PathVariable int id) {
        tx.executeWithoutResult(status -> {
            int deleted = jdbc.sql("DELETE FROM facility WHERE id = :id AND status = 'PLANNED'").param("id", id).update();
            if (deleted == 0) {
                throw new IllegalStateException("계획(PLANNED) 상태 시설만 삭제할 수 있습니다: " + id);
            }
            audit.record(currentUser.get(), "FACILITY_PLAN_DELETE", "facility", Integer.toString(id), Map.of());
        });
        changes.changed("facility");
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/facilities/planned")
    public Map<String, Integer> clearPlanned() {
        int deleted = tx.execute(status -> {
            int n = jdbc.sql("DELETE FROM facility WHERE status = 'PLANNED'").update();
            audit.record(currentUser.get(), "FACILITY_PLAN_CLEAR", "facility", null, Map.of("deleted", n));
            return n;
        });
        changes.changed("facility");
        return Map.of("deleted", deleted);
    }
}
