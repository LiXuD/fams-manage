package com.fams.repair;

import java.math.BigDecimal;

/** Private authorization ceiling for the entire repair, never an assertion of actual cost. */
record RepairFactoryCostPolicy(String mode, BigDecimal limit) {
    static RepairFactoryCostPolicy parse(String mode, String limit) {
        String selected = mode == null ? "NO_COST" : mode;
        BigDecimal amount = limit == null ? null : amount(limit);
        if ("NO_COST".equals(selected) && (amount == null || amount.signum() == 0)) {
            return new RepairFactoryCostPolicy(selected, new BigDecimal("0.00"));
        }
        if ("LIMITED".equals(selected) && amount != null && amount.signum() > 0) {
            return new RepairFactoryCostPolicy(selected, amount);
        }
        if ("UNLIMITED".equals(selected) && amount == null) {
            return new RepairFactoryCostPolicy(selected, null);
        }
        throw new RepairFailure(400, "REPAIR_FACTORY_COST_POLICY_INVALID");
    }

    static BigDecimal amount(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?")) {
            throw new RepairFailure(400, "REPAIR_FACTORY_COST_AMOUNT_INVALID");
        }
        return new BigDecimal(value).setScale(2);
    }

    String method() {
        return "NO_COST".equals(mode) ? "FACTORY_NO_COST" : "FACTORY_COST_ALLOWED";
    }

    boolean covers(BigDecimal amount) {
        return limit == null || amount.compareTo(limit) <= 0;
    }
}
