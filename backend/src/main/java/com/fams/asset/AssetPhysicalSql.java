package com.fams.asset;

/** Indexed scalar projection keeps ledger page reads batched; formal custody remains separate. */
final class AssetPhysicalSql {
    static final String PROJECTION =
            """
(SELECT JSON_OBJECT('allocationId',CAST(x.id AS CHAR),'requestId',CAST(x.request_id AS CHAR),
    'status',x.allocation_status,'destination',h.destination_text,
    'temporaryEmployeeId',CAST(h.temporary_employee_id AS CHAR),
    'deliveredAt',DATE_FORMAT(h.delivered_at,'%Y-%m-%dT%H:%i:%s.%fZ'),
    'receiptDeadlineAt',DATE_FORMAT(h.receipt_deadline_at,'%Y-%m-%dT%H:%i:%s.%fZ'))
 FROM fams_requisition_allocation x JOIN fams_requisition_handover h ON h.allocation_id=x.id
 WHERE x.asset_id=a.id AND x.active_guard=1
   AND x.allocation_status IN ('DELIVERED_WAITING_RECEIPT','RECOVERY_PENDING_CANCEL')) physical_destination_json
""";

    private AssetPhysicalSql() {}
}
