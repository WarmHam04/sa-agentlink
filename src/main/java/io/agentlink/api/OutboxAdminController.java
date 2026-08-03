package io.agentlink.api;

import io.agentlink.application.OutboxDeadLetter;
import io.agentlink.application.OutboxDeadLetterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/outbox")
public class OutboxAdminController {
    private final OutboxDeadLetterService deadLetters;
    private final TenantIdentityResolver tenants;

    public OutboxAdminController(OutboxDeadLetterService deadLetters, TenantIdentityResolver tenants) {
        this.deadLetters = deadLetters;
        this.tenants = tenants;
    }

    @GetMapping("/dead-letters")
    public List<OutboxDeadLetter> list(
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request
    ) {
        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 500");
        }
        return deadLetters.list(tenants.resolve(request).tenantId(), limit);
    }

    @PostMapping("/dead-letters/{outboxId}:replay")
    public void replay(@PathVariable long outboxId, HttpServletRequest request) {
        if (!deadLetters.replay(tenants.resolve(request).tenantId(), outboxId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dead letter not found");
        }
    }
}
