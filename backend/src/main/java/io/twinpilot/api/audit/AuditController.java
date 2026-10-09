package io.twinpilot.api.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.twinpilot.api.audit.AuditService.AuditEntry;

/** 감사 로그 조회(ADMIN). */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditEntry> search(@RequestParam(required = false) String action,
                                   @RequestParam(required = false) String actor,
                                   @RequestParam(defaultValue = "100") int limit) {
        return audit.search(action, actor, limit);
    }
}
