package com.fams.masterdata;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fams.auth.MasterDataActorContext;
import com.fams.product.contract.model.DisableEmployeeRequest;
import com.fams.product.contract.model.DisableOrganizationRequest;
import com.fams.product.contract.model.Employee;
import com.fams.product.contract.model.EmployeeCreateRequest;
import com.fams.product.contract.model.EmployeeUpdateRequest;
import com.fams.product.contract.model.EnableEmployeeRequest;
import com.fams.product.contract.model.Organization;
import com.fams.product.contract.model.OrganizationActiveReferencePage;
import com.fams.product.contract.model.OrganizationCreateRequest;
import com.fams.product.contract.model.OrganizationUpdateRequest;
import com.fams.product.contract.model.PageEmployee;
import com.fams.product.contract.model.PageOrganization;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1")
class IdentityMasterDataController {

    private final IdentityMasterDataService service;
    private final MasterDataActorContext actorContext;
    private final HttpServletRequest servletRequest;

    IdentityMasterDataController(
            IdentityMasterDataService service,
            MasterDataActorContext actorContext,
            HttpServletRequest servletRequest) {
        this.service = service;
        this.actorContext = actorContext;
        this.servletRequest = servletRequest;
    }

    @GetMapping("/organizations")
    ResponseEntity<PageOrganization> listOrganizations(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.listOrganizations(page, pageSize));
    }

    @GetMapping("/organizations/active-references")
    ResponseEntity<OrganizationActiveReferencePage> listActiveOrganizations(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.listActiveOrganizations(page, pageSize));
    }

    @GetMapping("/organizations/{organizationId}")
    ResponseEntity<Organization> getOrganization(@PathVariable String organizationId) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.getOrganization(parseId(organizationId)));
    }

    @PostMapping("/organizations")
    ResponseEntity<Organization> createOrganization(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody OrganizationCreateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        Organization result = service.createOrganization(
                actorId, body, idempotencyKey, RequestIds.from(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/organizations/{organizationId}")
    ResponseEntity<Organization> updateOrganization(
            @PathVariable String organizationId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody OrganizationUpdateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.updateOrganization(
                actorId,
                parseId(organizationId),
                expectedVersion,
                body,
                idempotencyKey,
                RequestIds.from(servletRequest)));
    }

    @PostMapping("/organizations/{organizationId}/disable")
    ResponseEntity<Organization> disableOrganization(
            @PathVariable String organizationId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody DisableOrganizationRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.disableOrganization(
                actorId,
                parseId(organizationId),
                expectedVersion,
                body.getReason(),
                idempotencyKey,
                RequestIds.from(servletRequest)));
    }

    @GetMapping("/employees")
    ResponseEntity<PageEmployee> listEmployees(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.listEmployees(page, pageSize));
    }

    @GetMapping("/employees/{employeeId}")
    ResponseEntity<Employee> getEmployee(@PathVariable String employeeId) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.getEmployee(parseId(employeeId)));
    }

    @PostMapping("/employees")
    ResponseEntity<Employee> createEmployee(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody EmployeeCreateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        Employee result = service.createEmployee(
                actorId, body, idempotencyKey, RequestIds.from(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/employees/{employeeId}")
    ResponseEntity<Employee> updateEmployee(
            @PathVariable String employeeId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody EmployeeUpdateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.updateEmployee(
                actorId,
                parseId(employeeId),
                expectedVersion,
                body,
                idempotencyKey,
                RequestIds.from(servletRequest)));
    }

    @PostMapping("/employees/{employeeId}/disable")
    ResponseEntity<Employee> disableEmployee(
            @PathVariable String employeeId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody DisableEmployeeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeEmployeeStatus(
                actorId,
                parseId(employeeId),
                expectedVersion,
                false,
                body.getReason(),
                idempotencyKey,
                RequestIds.from(servletRequest)));
    }

    @PostMapping("/employees/{employeeId}/enable")
    ResponseEntity<Employee> enableEmployee(
            @PathVariable String employeeId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody EnableEmployeeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeEmployeeStatus(
                actorId,
                parseId(employeeId),
                expectedVersion,
                true,
                body.getReason(),
                idempotencyKey,
                RequestIds.from(servletRequest)));
    }

    @GetMapping(value = "/identity-imports/template", produces = "text/csv")
    ResponseEntity<String> getIdentityImportTemplate() {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(IdentityImportParser.HEADER + "\n");
    }

    @PostMapping(
            value = "/identity-imports",
            consumes = "text/csv",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<IdentityImportResult> importIdentity(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @RequestBody @NotNull String csv) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.importIdentity(
                actorId, csv, idempotencyKey, RequestIds.from(servletRequest)));
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.notFound("The requested record");
        }
    }
}
