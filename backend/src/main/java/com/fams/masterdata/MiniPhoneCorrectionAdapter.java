package com.fams.masterdata;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.fams.auth.MiniPhoneCorrectionPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
class MiniPhoneCorrectionAdapter implements MiniPhoneCorrectionPort {
    private final JdbcTemplate jdbc;
    private final MasterDataPhoneCodec codec;

    MiniPhoneCorrectionAdapter(JdbcTemplate jdbc, MasterDataPhoneCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public Optional<Change> correct(long employeeId, long version, String phone, long actorId, Instant now) {
        List<String> rows = jdbc.query("SELECT mobile_ciphertext FROM fams_employee "
                + "WHERE id = ? AND version = ? FOR UPDATE", (row, ignored) -> {
                    byte[] value = row.getBytes(1);
                    return value == null ? "" : Base64.getEncoder().encodeToString(value);
                }, employeeId, version);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        byte[] encrypted = codec.encrypt(phone);
        jdbc.update("UPDATE fams_employee SET mobile_ciphertext = ?, mobile_lookup_hash = ?, version = version + 1, "
                + "updated_by = ?, updated_at = ? WHERE id = ? AND version = ?", encrypted, codec.lookupHash(phone),
                actorId, Timestamp.from(now), employeeId, version);
        return Optional.of(new Change(version + 1, rows.getFirst(), Base64.getEncoder().encodeToString(encrypted)));
    }
}
