package org.example.security;

import org.example.db.service.PendingSqlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/admin/sql")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApprovalController {
    private final PendingSqlStore store;
    public ApprovalController(PendingSqlStore store) { this.store = store; }

    public record Preview(String token, String owner, String dataSourceId, String sql, String sqlHash,
                          Instant expiresAt, boolean approved) {}
    public record ApprovalRequest(String sqlHash) {}

    @GetMapping("/{token}")
    public ResponseEntity<Preview> preview(@PathVariable String token) { return response(store.preview(token)); }

    @PostMapping("/{token}/approval")
    public ResponseEntity<Preview> approve(@PathVariable String token, @RequestBody ApprovalRequest request) {
        return response(store.approve(token, request.sqlHash()));
    }

    private ResponseEntity<Preview> response(PendingSqlStore.PendingSql pending) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new Preview(pending.token(), pending.owner(), pending.dataSourceId(), pending.sql().sql(),
                        pending.sqlHash(), pending.expiresAt(), pending.approved()));
    }

    @ExceptionHandler({IllegalArgumentException.class, SecurityException.class})
    ResponseEntity<String> invalid(RuntimeException e) {
        return ResponseEntity.status(400).cacheControl(CacheControl.noStore()).body("审批请求无效或已过期");
    }
}
