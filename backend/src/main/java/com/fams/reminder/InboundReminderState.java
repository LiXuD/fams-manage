package com.fams.reminder;

/** SQL predicates for the current inbound stage and its frozen deadline. */
final class InboundReminderState {
    private InboundReminderState() {}

    // A partial acceptance completes the first-execution milestone while the receipt stays in processing.
    static final String FIRST_ACCEPTANCE_PENDING = """
            NOT EXISTS (SELECT 1 FROM fams_inbound_receipt_item i
                WHERE i.receipt_id=x.id AND i.execution_status='ACCEPTED')
            """;
    static final String CURRENT_STAGE = """
            EXISTS (SELECT 1 FROM fams_inbound_receipt x WHERE x.id=r.business_id AND (
                (r.stage='ADMIN_DECISION' AND x.receipt_status='PENDING_ADMIN_DECISION'
                    AND x.admin_decision_due_at=r.deadline_at)
                OR (r.stage='WAREHOUSE_CLAIM' AND x.receipt_status='WAITING_WAREHOUSE_CLAIM'
                    AND x.warehouse_claim_due_at=r.deadline_at)
                OR (r.stage='FIRST_EXECUTION' AND x.receipt_status='WAREHOUSE_PROCESSING'
                    AND x.warehouse_first_execution_due_at=r.deadline_at AND
            """ + FIRST_ACCEPTANCE_PENDING + ")))";
}
