package com.fams.report;

import com.fams.auth.RequisitionActorContext;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.context.annotation.Profile;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@Profile("fams-product")
@Validated
class WorkbenchTodoController {
    private final RequisitionActorContext actors;
    private final WorkbenchTodoService todos;

    WorkbenchTodoController(RequisitionActorContext actors, WorkbenchTodoService todos) {
        this.actors = actors;
        this.todos = todos;
    }

    @ExceptionHandler(WorkbenchTodoService.Forbidden.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("WORKBENCH_SCOPE_FORBIDDEN", "Workbench scope is forbidden.", id, Map.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidFilter(HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", "Invalid workbench filter.", id, Map.of()));
    }

    @GetMapping("/api/v1/workbench/todos")
    ResponseEntity<WorkbenchTodoService.Page> list(@RequestParam WorkbenchTodoService.Scope scope,
            @RequestParam(required = false) @Positive Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(todos.list(actors.capture(), scope, before, pageSize));
    }
}
