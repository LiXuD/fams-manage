package com.fams.requisition;

import com.fams.attachment.AttachmentStorage;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.proxy.ProxyReceiptPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable signed evidence authorizes one physical receipt, never an automatic timeout receipt.
 */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
class RequisitionProxyConfirmationService {

    private final JdbcTemplate db;

    private final RequisitionActorContext actors;

    private final Map<String, ProxyReceiptPort> businesses;

    private final ObjectMapper json;

    private final Clock clock;

    private final ObjectProvider<AttachmentStorage> storage;

    RequisitionProxyConfirmationService(
            JdbcTemplate db,
            RequisitionActorContext actors,
            List<ProxyReceiptPort> businesses,
            ObjectMapper json,
            Clock clock,
            ObjectProvider<AttachmentStorage> storage) {
        this.db = db;
        this.actors = actors;
        this.businesses = businesses.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                ProxyReceiptPort::businessType, value -> value));
        this.json = json;
        this.clock = clock;
        this.storage = storage;
    }

    Map<String, Object> context(Actor actor, String businessType, long request, long item) {
        return business(businessType).snapshot(actor, request, item, true);
    }

    Map<String, Object> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN) && !current.has(Role.WAREHOUSE_OPERATOR)) {
            throw RequisitionFailure.forbidden();
        }
        String predicate =
                current.has(Role.ASSET_ADMIN)
                        ? "business_type IN ('REQUISITION','TRANSFER','REPAIR') AND (confirmation_status<>'DRAFT' OR creator_account_id="
                                + current.id() + ")"
                        : "business_type IN ('REQUISITION','TRANSFER','REPAIR') AND (creator_account_id="
                                + current.id()
                                + " OR (business_type='REQUISITION' AND EXISTS (SELECT 1 FROM fams_requisition_ownership o WHERE"
                                + " o.request_id=fams_proxy_confirmation.business_id AND"
                                + " o.archived_at IS NULL AND o.processor_account_id="
                                + current.id()
                                + ")) OR (business_type='TRANSFER' AND EXISTS (SELECT 1 FROM fams_transfer t"
                                + " WHERE t.id=fams_proxy_confirmation.business_id AND t.processor_account_id="
                                + current.id() + ")) OR (business_type='REPAIR' AND EXISTS (SELECT 1 FROM fams_repair_request p"
                                + " WHERE p.id=fams_proxy_confirmation.business_id AND p.processor_account_id="
                                + current.id() + ")))";
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_proxy_confirmation WHERE " + predicate,
                                Long.class));
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_proxy_confirmation WHERE "
                                + predicate
                                + " ORDER BY id DESC LIMIT ? OFFSET ?",
                        size,
                        (page - 1) * size);
        return Map.of(
                "items",
                rows.stream().map(this::wire).toList(),
                "page",
                page,
                "pageSize",
                size,
                "total",
                total,
                "canApprove", current.has(Role.ASSET_ADMIN),
                "canExecute", current.has(Role.WAREHOUSE_OPERATOR));
    }

    Map<String, Object> get(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var row = row(id, false);
        reader(current, row);
        return wire(row);
    }

    Map<String, Object> create(
            Actor actor, String key, RequisitionProxyConfirmationController.Draft input) {
        var current = role(actor, Role.WAREHOUSE_OPERATOR);
        String businessType = String.valueOf(input.snapshot().getOrDefault("businessType", "REQUISITION"));
        processor(current, businessType, input.businessId());
        var prior = replay(current, "createProxyConfirmation", key, input);
        if (prior != null) {
            return prior;
        }
        var snapshot = business(businessType).snapshot(actor, input.businessId(), input.itemId(), true);
        same(snapshot, input.snapshot());
        if (!Boolean.TRUE.equals(input.targetOrAuthorizedProxyReceived())
                || input.actualReceivedAt().isAfter(clock.instant())
                || !Set.of("SIGNED_RECEIPT", "LOGISTICS_RECEIPT", "AUTHORIZED_PROXY")
                        .contains(input.evidenceKind())
                || !Set.of("SCAN_CONFIRMED", "CODE_CONFIRMED", "MANUAL_RECORD")
                        .contains(input.collectionMethod())
                || "MANUAL_RECORD".equals(input.collectionMethod())
                        && blank(input.manualReason())) {
            throw invalid("PROXY_RECEIPT_EVIDENCE_REQUIRED");
        }
        if (input.delegatedReceipt()) {
            if (!"AUTHORIZED_PROXY".equals(input.evidenceKind())
                    || blank(input.delegationRelationship())
                    || blank(input.delegationBasis())) {
                throw invalid("PROXY_RECEIPT_DELEGATION_REQUIRED");
            }
        } else if (input.actualSignerEmployeeId() == null
                || !input.actualSignerEmployeeId()
                        .toString()
                        .equals(snapshot.get("targetEmployeeId"))) {
            throw invalid("PROXY_RECEIPT_SIGNER_MISMATCH");
        }
        if (!input.observedAssetCode().equals(snapshot.get("assetCode"))) {
            throw conflict("PROXY_RECEIPT_ASSET_MISMATCH");
        }
        if (input.previousConfirmationId() != null) {
            var previous = row(input.previousConfirmationId(), false);
            if (!businessType.equals(previous.get("business_type"))
                    || number(previous, "business_id") != input.businessId()
                    || number(previous, "item_id") != input.itemId()) {
                throw conflict("PROXY_RECEIPT_PREVIOUS_MISMATCH");
            }
        }
        Instant now = now();
        db.update(
                "INSERT INTO fams_proxy_confirmation"
                    + " (business_type,business_id,item_id,business_snapshot_json,evidence_metadata_json,previous_confirmation_id,observed_asset_code,actual_signer,actual_received_at,cannot_confirm_reason,contact_record,delegated_receipt,delegation_relationship,delegation_basis,evidence_kind,collection_method,manual_reason,creator_account_id,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                businessType,
                input.businessId(),
                input.itemId(),
                json.writeValueAsString(snapshot),
                json.writeValueAsString(input),
                input.previousConfirmationId(),
                input.observedAssetCode(),
                input.actualSigner(),
                input.actualReceivedAt(),
                input.cannotConfirmReason(),
                input.contactRecord(),
                input.delegatedReceipt(),
                input.delegationRelationship(),
                input.delegationBasis(),
                input.evidenceKind(),
                input.collectionMethod(),
                input.manualReason(),
                current.id(),
                now,
                now);
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        audit(
                actor,
                Role.WAREHOUSE_OPERATOR,
                "PROXY_RECEIPT_DRAFTED",
                id,
                0,
                0,
                Map.of("snapshot", snapshot, "evidenceMetadata", input));
        return finish(current, "createProxyConfirmation", key, input, id, wire(row(id, false)));
    }

    Map<String, Object> submit(Actor actor, long id, long version, String key) {
        var current = role(actor, Role.WAREHOUSE_OPERATOR);
        var hint = row(id, false);
        processor(current, hint.get("business_type").toString(), number(hint, "business_id"));
        var payload = Map.of("id", id, "version", version);
        var prior = replay(current, "submitProxyConfirmation", key, payload);
        if (prior != null) {
            return prior;
        }
        var row = lockedCurrent(actor, id, true);
        state(row, version, "DRAFT");
        if (number(row, "creator_account_id") != current.id()) {
            throw RequisitionFailure.forbidden();
        }
        var evidence = evidence(id);
        db.update(
                "UPDATE fams_proxy_confirmation SET"
                    + " confirmation_status='SUBMITTED',evidence_snapshot_json=?,submitted_at=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                json.writeValueAsString(evidence),
                now(),
                now(),
                id);
        audit(
                actor,
                Role.WAREHOUSE_OPERATOR,
                "PROXY_RECEIPT_SUBMITTED",
                id,
                version,
                version + 1,
                Map.of("evidence", evidence));
        return finish(current, "submitProxyConfirmation", key, payload, id, wire(row(id, false)));
    }

    Map<String, Object> decision(
            Actor actor,
            long id,
            long version,
            String key,
            RequisitionProxyConfirmationController.Decision input) {
        var current = role(actor, Role.ASSET_ADMIN);
        var payload = Map.of("id", id, "version", version, "decision", input);
        var prior = replay(current, "decideProxyConfirmation", key, payload);
        if (prior != null) {
            return prior;
        }
        var row = input.approved() ? lockedCurrent(actor, id, false) : row(id, true);
        state(row, version, "SUBMITTED");
        if (input.approved()) {
            if (!input.physicalEvidenceChecked() || !input.noDisputeChecked()) {
                throw invalid("PROXY_RECEIPT_APPROVAL_CHECK_REQUIRED");
            }
            frozen(row);
        }
        db.update(
                "UPDATE fams_proxy_confirmation SET"
                    + " confirmation_status=?,approver_account_id=?,decision_reason=?,decided_at=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                input.approved() ? "APPROVED" : "REJECTED",
                current.id(),
                input.reason(),
                now(),
                now(),
                id);
        audit(
                actor,
                Role.ASSET_ADMIN,
                input.approved() ? "PROXY_RECEIPT_APPROVED" : "PROXY_RECEIPT_REJECTED",
                id,
                version,
                version + 1,
                Map.of("decision", input));
        return finish(current, "decideProxyConfirmation", key, payload, id, wire(row(id, false)));
    }

    Map<String, Object> execute(Actor actor, long id, long version, String key) {
        var current = role(actor, Role.WAREHOUSE_OPERATOR);
        var hint = row(id, false);
        processor(current, hint.get("business_type").toString(), number(hint, "business_id"));
        var payload = Map.of("id", id, "version", version);
        var prior = replay(current, "executeProxyConfirmation", key, payload);
        if (prior != null) {
            return prior;
        }
        var row = lockedCurrent(actor, id, true);
        state(row, version, "APPROVED");
        frozen(row);
        @SuppressWarnings("unchecked")
        var snapshot = json.readValue(row.get("business_snapshot_json").toString(), Map.class);
        var result =
                business(row.get("business_type").toString()).execute(
                        actor,
                        number(row, "business_id"),
                        number(row, "item_id"),
                        snapshot,
                        row.get("observed_asset_code").toString(),
                        id,
                        executionEvidence(row));
        db.update(
                "UPDATE fams_proxy_confirmation SET"
                    + " confirmation_status='EXECUTED',executor_account_id=?,executed_at=?,execution_result_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                current.id(),
                now(),
                json.writeValueAsString(result),
                now(),
                id);
        var detail = new LinkedHashMap<String, Object>();
        detail.put("result", result);
        detail.put("businessSnapshot", snapshot);
        detail.put("actualSigner", row.get("actual_signer"));
        detail.put("submittedBy", row.get("creator_account_id").toString());
        detail.put("approvedBy", row.get("approver_account_id").toString());
        detail.put("executedBy", Long.toString(current.id()));
        detail.put("evidence", json.readTree(row.get("evidence_snapshot_json").toString()));
        detail.put("metadata", json.readTree(row.get("evidence_metadata_json").toString()));
        audit(
                actor,
                Role.WAREHOUSE_OPERATOR,
                "PROXY_RECEIPT_EXECUTED",
                id,
                version,
                version + 1,
                detail);
        db.update(
                "INSERT INTO fams_business_result_notice"
                    + " (execution_type,execution_id,recipient_employee_id,business_type,business_id,item_id,content_json,created_at)"
                    + " VALUES ('PROXY_CONFIRMATION',?,?,?,?,?,?,?)",
                id,
                Long.parseLong(result.get("targetEmployeeId").toString()),
                row.get("business_type"),
                number(row, "business_id"),
                number(row, "item_id"),
                json.writeValueAsString(detail),
                now());
        return finish(current, "executeProxyConfirmation", key, payload, id, wire(row(id, false)));
    }

    private ProxyReceiptPort.ExecutionEvidence executionEvidence(Map<String, Object> row) {
        var metadata = json.readTree(row.get("evidence_metadata_json").toString());
        var signer = metadata.path("actualSignerEmployeeId");
        Object time = row.get("actual_received_at");
        Instant actual = time instanceof java.sql.Timestamp timestamp ? timestamp.toInstant()
                : ((java.time.LocalDateTime) time).toInstant(java.time.ZoneOffset.UTC);
        return new ProxyReceiptPort.ExecutionEvidence(actual, row.get("actual_signer").toString(),
                signer.isNull() || signer.isMissingNode() ? null : signer.asLong(),
                metadata.path("delegatedReceipt").asBoolean(), number(row,"approver_account_id"),
                row.get("evidence_metadata_json").toString(), row.get("evidence_snapshot_json").toString());
    }

    private ProxyReceiptPort business(String type) {
        var port = businesses.get(type);
        if (port == null) { throw invalid("PROXY_RECEIPT_BUSINESS_TYPE_INVALID"); }
        return port;
    }

    private void processor(Current current, String type, long request) {
        business(type).requireProcessor(current.id(), request);
    }

    private Map<String, Object> lockedCurrent(Actor actor, long id, boolean executing) {
        var hint = row(id, false);
        var snapshot =
                business(hint.get("business_type").toString()).snapshot(
                        actor, number(hint, "business_id"), number(hint, "item_id"), executing);
        var row = row(id, true);
        same(snapshot, json.readTree(row.get("business_snapshot_json").toString()));
        return row;
    }

    private void frozen(Map<String, Object> row) {
        same(
                evidence(number(row, "id")),
                json.readTree(row.get("evidence_snapshot_json").toString()));
    }

    private List<Map<String, Object>> evidence(long id) {
        var rows =
                db.queryForList(
                        "SELECT"
                            + " a.object_id,a.content_sha256,a.size_bytes,a.original_filename,a.business_version,a.uploader_account_id,a.created_at,a.scan_status"
                            + " FROM fams_attachment a WHERE a.business_type='PROXY_CONFIRMATION'"
                            + " AND a.business_id=? AND a.deleted_at IS NULL AND NOT EXISTS (SELECT"
                            + " 1 FROM fams_attachment_revision r WHERE"
                            + " r.previous_object_id=a.object_id) ORDER BY a.object_id FOR UPDATE",
                        id);
        if (rows.isEmpty()) {
            throw conflict("PROXY_RECEIPT_SIGNED_ATTACHMENT_REQUIRED");
        }
        var store = storage.getIfAvailable();
        if (store == null) {
            throw conflict("PROXY_RECEIPT_EVIDENCE_UNAVAILABLE");
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            if (!"CLEAN".equals(row.get("scan_status"))) {
                throw conflict("PROXY_RECEIPT_ATTACHMENT_NOT_CLEAN");
            }
            long size = number(row, "size_bytes");
            if (size <= 0 || size > 20L * 1024 * 1024) {
                throw conflict("PROXY_RECEIPT_ATTACHMENT_CHANGED");
            }
            try (var stream = store.open(UUID.fromString(row.get("object_id").toString()))) {
                byte[] bytes = stream.readNBytes((int) size + 1);
                if (bytes.length != size || !hash(bytes).equals(row.get("content_sha256"))) {
                    throw conflict("PROXY_RECEIPT_ATTACHMENT_CHANGED");
                }
            } catch (IOException error) {
                throw conflict("PROXY_RECEIPT_EVIDENCE_UNAVAILABLE");
            }
            var clean = new LinkedHashMap<String, Object>();
            row.forEach((k, v) -> clean.put(k, v == null ? null : v.toString()));
            result.add(clean);
        }
        return result;
    }

    private Current role(Actor actor, Role role) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(role)) {
            throw RequisitionFailure.forbidden();
        }
        return current;
    }

    private Map<String, Object> row(long id, boolean lock) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_proxy_confirmation WHERE id=? AND"
                            + " business_type IN ('REQUISITION','TRANSFER','REPAIR')"
                                + (lock ? " FOR UPDATE" : ""),
                        id);
        if (rows.isEmpty()) {
            throw RequisitionFailure.unavailable();
        }
        return rows.getFirst();
    }

    private void reader(Current current, Map<String, Object> row) {
        if (current.has(Role.ASSET_ADMIN) && !"DRAFT".equals(row.get("confirmation_status"))
                || current.has(Role.WAREHOUSE_OPERATOR)
                        && number(row, "creator_account_id") == current.id()) {
            return;
        }
        if (current.has(Role.WAREHOUSE_OPERATOR)
                && business(row.get("business_type").toString()).currentProcessor(
                        current.id(), number(row, "business_id"), false)) {
            return;
        }
        throw RequisitionFailure.unavailable();
    }

    private void state(Map<String, Object> row, long version, String state) {
        if (number(row, "version") != version || !state.equals(row.get("confirmation_status"))) {
            throw conflict("PROXY_RECEIPT_VERSION_CONFLICT");
        }
    }

    private Map<String, Object> wire(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach(
                (k, v) -> {
                    StringBuilder key = new StringBuilder();
                    boolean upper = false;
                    for (char c : k.toCharArray()) {
                        if (c == '_') {
                            upper = true;
                        } else {
                            key.append(upper ? Character.toUpperCase(c) : c);
                            upper = false;
                        }
                    }
                    result.put(
                            key.toString(),
                            v == null
                                    ? null
                                    : k.endsWith("_json")
                                            ? json.readTree(v.toString())
                                            : k.equals("id") || k.endsWith("_id")
                                                    ? v.toString()
                                                    : v);
                });
        return result;
    }

    private void audit(
            Actor actor,
            Role role,
            String action,
            long id,
            long before,
            long after,
            Object detail) {
        db.update(
                "INSERT INTO fams_audit_event"
                    + " (request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'PROXY_CONFIRMATION',?,?,?,?,?)",
                actor.requestId().toString(),
                actor.id(),
                role.name(),
                action,
                id,
                before,
                after,
                now(),
                json.writeValueAsString(detail));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> replay(
            Current current, String operation, String key, Object payload) {
        db.update("DELETE FROM fams_idempotency_record WHERE actor_account_id=?"
                + " AND operation_id=? AND idempotency_key=? AND expires_at<=CURRENT_TIMESTAMP(3)",
                current.id(), operation, key);
        var rows =
                db.queryForList(
                        "SELECT payload_sha256,response_body_json FROM fams_idempotency_record"
                            + " WHERE actor_account_id=? AND operation_id=? AND idempotency_key=?"
                            + " AND expires_at>CURRENT_TIMESTAMP(3) FOR UPDATE",
                        current.id(),
                        operation,
                        key);
        if (rows.isEmpty()) {
            return null;
        }
        if (!ProxyReceiptPayloads.matches(json, payload, rows.getFirst().get("payload_sha256").toString())) {
            throw conflict("PROXY_RECEIPT_IDEMPOTENCY_CONFLICT");
        }
        return json.readValue(rows.getFirst().get("response_body_json").toString(), Map.class);
    }

    private Map<String, Object> finish(
            Current current,
            String operation,
            String key,
            Object payload,
            long id,
            Map<String, Object> result) {
        db.update(
                "INSERT INTO fams_idempotency_record"
                    + " (actor_account_id,operation_id,idempotency_key,payload_sha256,result_status,http_status,response_body_json,resource_type,resource_id,resource_version,created_at,completed_at,expires_at)"
                    + " VALUES (?,?,?,?,'SUCCEEDED',200,?,'PROXY_CONFIRMATION',?,?,?,?,?)",
                current.id(),
                operation,
                key,
                digest(payload),
                json.writeValueAsString(result),
                id,
                result.get("version"),
                now(),
                now(),
                now().plus(24, ChronoUnit.HOURS));
        return result;
    }

    private void same(Object actual, Object expected) {
        var expectedNode =
                expected instanceof tools.jackson.databind.JsonNode node
                        ? node
                        : json.readTree(json.writeValueAsString(expected));
        if (!json.readTree(json.writeValueAsString(actual)).equals(expectedNode)) {
            throw conflict("PROXY_RECEIPT_SCOPE_CHANGED");
        }
    }

    private String digest(Object value) {
        return ProxyReceiptPayloads.digest(json, value);
    }

    private String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private RequisitionFailure conflict(String code) {
        return RequisitionFailure.conflict(code);
    }

    private RequisitionFailure invalid(String code) {
        return RequisitionFailure.invalid(code);
    }
}
