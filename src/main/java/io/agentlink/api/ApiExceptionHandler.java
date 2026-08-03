package io.agentlink.api;

import io.agentlink.application.AgentRegistrationConflictException;
import io.agentlink.application.TenantAccessDeniedException;
import io.agentlink.application.TenantQuotaExceededException;
import io.agentlink.application.TenantRegionMismatchException;
import io.agentlink.application.TenantRoutingConflictException;
import io.agentlink.application.WorkflowNotFoundException;
import io.agentlink.application.WorkflowLeaseLostException;
import io.agentlink.application.WorkflowLeaseUnavailableException;
import io.agentlink.application.WorkflowOptimisticLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(WorkflowNotFoundException.class)
    public ProblemDetail notFound(WorkflowNotFoundException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, failure.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ProblemDetail badRequest(Exception failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, failure.getMessage());
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    public ProblemDetail forbidden(TenantAccessDeniedException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, failure.getMessage());
    }

    @ExceptionHandler(TenantQuotaExceededException.class)
    public ResponseEntity<ProblemDetail> tooManyRequests(TenantQuotaExceededException failure) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, failure.getMessage()
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(failure.retryAfterSeconds()))
                .body(detail);
    }

    @ExceptionHandler(TenantRegionMismatchException.class)
    public ProblemDetail wrongRegion(TenantRegionMismatchException failure) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage());
        detail.setProperty("homeRegion", failure.homeRegion());
        return detail;
    }

    @ExceptionHandler({
            WorkflowLeaseLostException.class,
            WorkflowLeaseUnavailableException.class,
            WorkflowOptimisticLockException.class,
            AgentRegistrationConflictException.class,
            TenantRoutingConflictException.class
    })
    public ProblemDetail conflict(RuntimeException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage());
    }
}
