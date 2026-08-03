package io.agentlink.api;

import io.agentlink.application.AgentRegistrationConflictException;
import io.agentlink.application.WorkflowNotFoundException;
import io.agentlink.application.WorkflowLeaseLostException;
import io.agentlink.application.WorkflowLeaseUnavailableException;
import io.agentlink.application.WorkflowOptimisticLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    @ExceptionHandler({
            WorkflowLeaseLostException.class,
            WorkflowLeaseUnavailableException.class,
            WorkflowOptimisticLockException.class,
            AgentRegistrationConflictException.class
    })
    public ProblemDetail conflict(RuntimeException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage());
    }
}
