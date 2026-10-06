package com.fams.masterdata;

import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Authentication receives a versioned identity result, never the registered phone value. */
@Component
@Profile("fams-product")
public class MiniEmployeeIdentityPort implements com.fams.auth.MiniEmployeeIdentityPort {
    private final JdbcTemplate jdbc;
    private final MasterDataPhoneCodec codec;

    MiniEmployeeIdentityPort(JdbcTemplate jdbc, MasterDataPhoneCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public Optional<Long> findEmployee(String employeeNo) {
        String normalized = Normalizer.normalize(employeeNo.strip(), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        List<Long> ids = jdbc.queryForList("SELECT id FROM fams_employee WHERE employee_no_normalized = ?",
                Long.class, normalized);
        return ids.stream().findFirst();
    }

    /** Caller owns an account transaction. The employee lock prevents phone correction races. */
    public Optional<Long> verify(long employeeId, String suppliedPhone) {
        byte[] supplied = codec.lookupHash(suppliedPhone);
        List<Long> matches = jdbc.query("SELECT version, status, mobile_lookup_hash FROM fams_employee "
                + "WHERE id = ? FOR UPDATE", (row, ignored) -> {
                    byte[] registered = row.getBytes(3);
                    return "ACTIVE".equals(row.getString(2)) && supplied != null && registered != null
                            && MessageDigest.isEqual(supplied, registered) ? row.getLong(1) : -1L;
                }, employeeId);
        return matches.stream().filter(version -> version >= 0).findFirst();
    }

    public boolean isCurrent(long employeeId, long expectedVersion) {
        return jdbc.queryForList("SELECT id FROM fams_employee WHERE id = ? AND version = ? "
                + "AND status = 'ACTIVE' FOR UPDATE", Long.class, employeeId, expectedVersion).size() == 1;
    }
}
