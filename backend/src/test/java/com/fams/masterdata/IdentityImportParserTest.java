package com.fams.masterdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class IdentityImportParserTest {

    private final IdentityImportParser parser = new IdentityImportParser();

    @Test
    void quotedFieldsAndLineEndingsPreserveEightColumnStructure() {
        String csv = IdentityImportParser.HEADER + "\r\n"
                + "ORGANIZATION,CREATE,OPS,\"Operations, East\",ROOT,,,\r\n"
                + "EMPLOYEE,UPDATE,E200,\"Lee \"\"L\"\"\",,OPS,,3\n"
                + "\n";
        List<IdentityImportRow> rows = parser.parse(csv);
        assertEquals(2, rows.size());
        assertEquals("Operations, East", rows.get(0).displayName());
        assertEquals("Lee \"L\"", rows.get(1).displayName());
        assertEquals(3L, rows.get(1).expectedVersion());
    }

    @Test
    void rejectsMissingOrNonstandardTemplate() {
        assertEquals("MASTER_DATA_INVALID", invalid("").code());
        assertEquals("MASTER_DATA_INVALID", invalid("wrong,header\n").code());
        assertEquals("MASTER_DATA_INVALID", invalid(IdentityImportParser.HEADER + "\n").code());
        String many = IdentityImportParser.HEADER + "\n"
                + "ORGANIZATION,CREATE,X,Name,,,,\n".repeat(501);
        assertEquals("MASTER_DATA_INVALID", invalid(many).code());
    }

    @Test
    void rejectsMalformedRowsBeforeAnyWrite() {
        String header = IdentityImportParser.HEADER + "\n";
        assertEquals("MASTER_DATA_INVALID", invalid(header + "ORGANIZATION,CREATE,X\n").code());
        assertEquals("MASTER_DATA_INVALID", invalid(header
                + "ORGANIZATION,CREATE,,Name,,,,\n").code());
        assertEquals("MASTER_DATA_INVALID", invalid(header
                + "OTHER,CREATE,X,Name,,,,\n").code());
        assertEquals("MASTER_DATA_INVALID", invalid(header
                + "ORGANIZATION,REMOVE,X,Name,,,,\n").code());
        assertEquals("MASTER_DATA_INVALID", invalid(header
                + "ORGANIZATION,UPDATE,X,Name,,,,negative\n").code());
        assertEquals("MASTER_DATA_INVALID", invalid(header
                + "ORGANIZATION,UPDATE,X,Name,,,,\"unclosed\n").code());
    }

    private MasterDataOperationException invalid(String csv) {
        return assertThrows(MasterDataOperationException.class, () -> parser.parse(csv));
    }
}
