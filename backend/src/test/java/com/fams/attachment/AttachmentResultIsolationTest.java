package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import com.fams.auth.AttachmentReaderContext;
import com.fams.auth.Role;
import org.junit.jupiter.api.Test;

class AttachmentResultIsolationTest {
    @Test
    void contentDoesNotShareCallerMutableBytes() {
        byte[] original = {1, 2, 3};
        var content = new AttachmentReadService.Content("doc.pdf", "application/pdf", original);
        original[0] = 9;
        content.bytes()[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, content.bytes());
    }

    @Test
    void authorizationSnapshotDoesNotShareMutableRoles() {
        var roles = new HashSet<>(Set.of(Role.WAREHOUSE_OPERATOR));
        var reader = new AttachmentReaderContext.Reader(1, roles, 0, 0, Instant.MAX);
        roles.add(Role.ASSET_ADMIN);
        assertEquals(Set.of(Role.WAREHOUSE_OPERATOR), reader.roles());
        assertThrows(UnsupportedOperationException.class, () -> reader.roles().add(Role.ASSET_ADMIN));
    }
}
