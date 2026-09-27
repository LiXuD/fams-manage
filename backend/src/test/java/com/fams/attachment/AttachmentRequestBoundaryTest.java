package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.InputStream;
import java.util.UUID;
import java.util.stream.Stream;
import com.fams.auth.AttachmentActorContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AttachmentRequestBoundaryTest {
    record Request(String key, long businessId, long version, String filename, String mime,
                   UUID previous, String reason) { }

    static Stream<Request> invalidRequests() {
        UUID previous = UUID.randomUUID();
        String key = "boundary-key-0001";
        return Stream.of(
                new Request(null, 1, 0, "doc.pdf", "application/pdf", previous, "Correction"),
                new Request("short", 1, 0, "doc.pdf", "application/pdf", previous, "Correction"),
                new Request("x".repeat(129), 1, 0, "doc.pdf", "application/pdf", previous, "Correction"),
                new Request("contains space 01", 1, 0, "doc.pdf", "application/pdf", previous, "Correction"),
                new Request(key, 0, 0, "doc.pdf", "application/pdf", previous, "Correction"),
                new Request(key, 1, -1, "doc.pdf", "application/pdf", previous, "Correction"),
                new Request(key, 1, 0, null, "application/pdf", previous, "Correction"),
                new Request(key, 1, 0, " ", "application/pdf", previous, "Correction"),
                new Request(key, 1, 0, "x".repeat(256), "application/pdf", previous, "Correction"),
                new Request(key, 1, 0, "doc.pdf", null, previous, "Correction"));
    }

    static Stream<Request> invalidCorrections() {
        String key = "boundary-key-0001";
        UUID previous = UUID.randomUUID();
        return Stream.concat(invalidRequests(), Stream.of(
                new Request(key, 1, 0, "doc.pdf", "application/pdf", null, "Correction"),
                new Request(key, 1, 0, "doc.pdf", "application/pdf", previous, null),
                new Request(key, 1, 0, "doc.pdf", "application/pdf", previous, " \t"),
                new Request(key, 1, 0, "doc.pdf", "application/pdf", previous, "x".repeat(501))));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidUploadDoesNotReadOrRegister(Request request) {
        reject(request, false);
    }

    @ParameterizedTest
    @MethodSource("invalidCorrections")
    void invalidCorrectionDoesNotReadOrRegister(Request request) {
        reject(request, true);
    }

    private void reject(Request request, boolean correction) {
        var writer = mock(AttachmentWriter.class);
        var registry = mock(AttachmentRegistry.class);
        var actors = mock(AttachmentActorContext.class);
        var business = mock(AttachmentBusinessPort.class);
        var keys = mock(AttachmentUploadKeys.class);
        var source = mock(InputStream.class);
        var service = new AttachmentUploadService(writer, registry, actors, business);
        if (correction) {
            assertThrows(IllegalArgumentException.class, () -> service.correctIdempotent(keys, request.key(),
                    request.businessId(), request.version(), request.previous(), request.reason(),
                    request.filename(), request.mime(), source));
        } else {
            assertThrows(IllegalArgumentException.class, () -> service.uploadIdempotent(keys, request.key(),
                    request.businessId(), request.version(), request.filename(), request.mime(), source));
        }
        verifyNoInteractions(writer, registry, actors, business, keys, source);
    }
}
