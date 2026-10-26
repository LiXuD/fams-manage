package com.fams.repair;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Profile("fams-product")
@Validated
@RestController
@RequestMapping({"/api/v1/repairs/{id}/factory", "/api/v1/mini/repairs/{id}/factory"})
public class RepairFactoryController {
    private final RepairFactoryService factory;
    private final RequisitionActorContext actors;

    public RepairFactoryController(RepairFactoryService factory, RequisitionActorContext actors) {
        this.factory = factory;
        this.actors = actors;
    }

    @GetMapping
    Map<String, Object> get(@PathVariable @Positive long id) {
        return factory.get(actors.capture(), id);
    }

    @GetMapping("/rounds")
    Map<String, Object> rounds(
            @PathVariable @Positive long id,
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return factory.rounds(actors.capture(), id, page, pageSize);
    }

    @GetMapping("/rounds/{round}")
    Map<String, Object> round(@PathVariable @Positive long id, @PathVariable @Positive long round) {
        return factory.round(actors.capture(), id, round);
    }

    @GetMapping("/history")
    Map<String, Object> history(
            @PathVariable @Positive long id,
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return factory.history(actors.capture(), id, page, pageSize);
    }

    @PostMapping("/scopes")
    Map<String, Object> requestScope(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.ScopeRequest input) {
        var actor = actors.capture();
        return retry(() -> factory.requestScope(actor, id, version, key, input));
    }

    @PostMapping("/scope-decision")
    Map<String, Object> decideScope(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.ScopeDecision input) {
        var actor = actors.capture();
        return retry(() -> factory.decideScope(actor, id, version, key, input));
    }

    @PostMapping("/scope-withdrawal")
    Map<String, Object> withdrawScope(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.ScopeDecision input) {
        var actor = actors.capture();
        return retry(() -> factory.withdrawScope(actor, id, version, key, input));
    }

    @PostMapping("/rounds")
    Map<String, Object> createRound(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.RoundCreation input) {
        var actor = actors.capture();
        return retry(() -> factory.createRound(actor, id, version, key, input));
    }

    @PostMapping("/rounds/{round}/cancel")
    Map<String, Object> cancelRound(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.RoundCancellation input) {
        var actor = actors.capture();
        return retry(() -> factory.cancelRound(actor, id, round, version, key, input));
    }

    @PostMapping("/rounds/{round}/dispatch")
    Map<String, Object> dispatch(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.Dispatch input) {
        var actor = actors.capture();
        return retry(() -> factory.dispatch(actor, id, round, version, key, input));
    }

    @PostMapping("/rounds/{round}/receive-original")
    Map<String, Object> receive(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.OriginalReceipt input) {
        var actor = actors.capture();
        return retry(() -> factory.receive(actor, id, round, version, key, input));
    }

    @PostMapping("/rounds/{round}/quotes")
    Map<String, Object> quote(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.MoneyFact input) {
        var actor = actors.capture();
        return retry(() -> factory.recordQuote(actor, id, round, version, key, input));
    }

    @PostMapping("/rounds/{round}/cost")
    Map<String, Object> cost(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.MoneyFact input) {
        var actor = actors.capture();
        return retry(() -> factory.recordCost(actor, id, round, version, key, input));
    }

    @GetMapping("/rounds/{round}/parts")
    Map<String, Object> parts(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return factory.parts(actors.capture(), id, round, page, pageSize);
    }

    @PostMapping("/rounds/{round}/parts")
    Map<String, Object> recordParts(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairFactoryInput.PartsFact input) {
        var actor = actors.capture();
        return retry(() -> factory.recordParts(actor, id, round, version, key, input));
    }

    private Map<String, Object> retry(Supplier<Map<String, Object>> command) {
        try {
            return command.get();
        } catch (org.springframework.dao.CannotAcquireLockException conflict) {
            return command.get();
        }
    }
}
