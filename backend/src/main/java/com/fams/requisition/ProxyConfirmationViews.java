package com.fams.requisition;

import com.fams.attachment.AttachmentIntake;

import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the proxy confirmation receipt HTTP exits (JAVA-14b3): the attachment
 * list envelope, the upload/correction receipts and the mini-program download grant. The
 * confirmation envelope itself (ProxyViews) has been typed since JAVA-04 and stays frozen by
 * the wire-shape oracles. Field names, key sets and id stringification replicate the pre-batch
 * Map assemblies verbatim; the global mapper (JsonConfiguration ALWAYS) still emits every
 * nullable component as an explicit null, exactly like the previous maps.
 */
public final class ProxyConfirmationViews {
    private ProxyConfirmationViews() {
    }

    /** Envelope of the attachment list exit (pre-batch {@code Map.of("items", ...)}). */
    public record AttachmentListPage(List<AttachmentEvidence> items) {
        public AttachmentListPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Alias row of {@code AttachmentReadService.list} for PROXY_CONFIRMATION. createdAt keeps
     * the raw JDBC value (Timestamp or LocalDateTime across drivers), mirroring the
     * AttachmentEvidencePort.Metadata precedent; the three revision keys are nullable and stay
     * explicitly present on the wire, as the queryForList map always carried them.
     */
    public record AttachmentEvidence(
            String objectId,
            String filename,
            String contentType,
            long sizeBytes,
            String scanStatus,
            long businessVersion,
            Object createdAt,
            long revisionNumber,
            String previousObjectId,
            String correctionReason,
            String nextObjectId) {
    }

    /** Single production JDBC-alias-row mapping, frozen by the wire-shape oracles. */
    public static AttachmentEvidence attachmentEvidence(Map<String, Object> row) {
        return new AttachmentEvidence(
                (String) row.get("objectId"),
                (String) row.get("filename"),
                (String) row.get("contentType"),
                ((Number) row.get("sizeBytes")).longValue(),
                (String) row.get("scanStatus"),
                ((Number) row.get("businessVersion")).longValue(),
                row.get("createdAt"),
                ((Number) row.get("revisionNumber")).longValue(),
                (String) row.get("previousObjectId"),
                (String) row.get("correctionReason"),
                (String) row.get("nextObjectId"));
    }

    /**
     * 201 receipt of an idempotent attachment upload or correction; six keys exactly as the
     * pre-batch {@code Map.of} assembly, objectId stringified from the stored UUID.
     */
    public record EvidenceReceipt(
            String objectId,
            String filename,
            String contentType,
            long sizeBytes,
            String scanStatus,
            long businessVersion) {
    }

    public static EvidenceReceipt receipt(
            AttachmentIntake.QuarantinedAttachment result, String filename, long version) {
        return new EvidenceReceipt(
                result.file().id().toString(),
                filename,
                result.format().mime(),
                result.file().size(),
                result.scanResult().name(),
                version + 1);
    }

    /** Mini download grant: the attachment grant Map's two keys with the mini path rewrite. */
    public record DownloadGrant(String url, long expiresAtEpochSecond) {
    }
}
