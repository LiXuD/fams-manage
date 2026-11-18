package com.fams.asset;

/** Read-only predicates over the official workflow facts linked to a visible asset. */
final class AssetLedgerWorkflowSql {
    static final String PROCESSOR = """
            (
                EXISTS (
                    SELECT 1 FROM fams_asset_business_lock held
                    LEFT JOIN fams_inbound_processing_ownership inbound ON held.business_type='INBOUND'
                        AND inbound.receipt_id=held.business_id AND inbound.active_guard=1
                    LEFT JOIN fams_requisition_ownership requisition ON held.business_type='REQUISITION'
                        AND requisition.request_id=held.business_id AND requisition.active_guard=1
                    LEFT JOIN fams_transfer transfer ON held.business_type='TRANSFER' AND transfer.id=held.business_id
                        AND transfer.request_status IN ('PROCESSING','WAITING_HANDOVER')
                    LEFT JOIN fams_asset_return returned ON held.business_type='ASSET_RETURN' AND returned.id=held.business_id
                        AND returned.return_status='WAREHOUSE_PROCESSING'
                    LEFT JOIN fams_repair_request repair ON held.business_type='REPAIR' AND repair.id=held.business_id
                        AND repair.repair_status='WAREHOUSE_PROCESSING'
                    LEFT JOIN fams_loss_report loss ON held.business_type='LOST_REPORT' AND loss.id=held.business_id
                        AND loss.report_status IN ('VERIFYING','SEARCHING')
                    LEFT JOIN fams_loss_recovery recovery ON held.business_type='LOST_REPORT'
                        AND recovery.report_id=held.business_id AND recovery.id=held.business_item_id
                        AND recovery.recovery_status='VERIFYING'
                    WHERE held.asset_id=a.id AND held.active_guard=1
                        AND COALESCE(inbound.processor_account_id,requisition.processor_account_id,
                            transfer.processor_account_id,returned.processor_account_id,repair.processor_account_id,
                            recovery.processor_account_id,loss.processor_account_id)=#{filters.currentProcessorId}
                )
                OR EXISTS (
                    SELECT 1 FROM fams_scrap_request scrap
                    JOIN fams_todo work ON work.business_type='SCRAP' AND work.business_id=scrap.id
                        AND work.pool_type='WAREHOUSE_OPERATOR' AND work.todo_status='OPEN' AND work.active_guard=1
                    WHERE scrap.asset_id=a.id AND work.assignee_account_id=#{filters.currentProcessorId}
                )
            )
            """;

    static final String REFERENCE = """
            (
                EXISTS (
                    SELECT 1 FROM fams_inbound_receipt_item item JOIN fams_inbound_receipt receipt ON receipt.id=item.receipt_id
                    WHERE item.asset_id=a.id AND (receipt.receipt_status&lt;&gt;'DRAFT' OR receipt.creator_account_id=#{actorId})
                        AND (receipt.id=#{filters.businessReference.id} OR receipt.receipt_no=#{filters.businessReference.number})
                )
                OR EXISTS (
                    SELECT 1 FROM fams_requisition_allocation item JOIN fams_requisition request ON request.id=item.request_id
                    WHERE item.asset_id=a.id
                        AND (request.id=#{filters.businessReference.id} OR request.request_no=#{filters.businessReference.number})
                )
                OR EXISTS (
                    SELECT 1 FROM fams_transfer_item item JOIN fams_transfer request ON request.id=item.request_id
                    WHERE item.asset_id=a.id AND request.id=#{filters.businessReference.id}
                        AND (request.request_status&lt;&gt;'DRAFT' OR request.creator_account_id=#{actorId})
                )
                OR EXISTS (SELECT 1 FROM fams_asset_return r WHERE r.id=#{filters.businessReference.id} AND r.asset_id=a.id)
                OR EXISTS (SELECT 1 FROM fams_loan l WHERE l.id=#{filters.businessReference.id} AND l.asset_id=a.id)
                OR EXISTS (SELECT 1 FROM fams_repair_request p WHERE p.id=#{filters.businessReference.id} AND p.asset_id=a.id)
                OR EXISTS (SELECT 1 FROM fams_loss_report l WHERE l.id=#{filters.businessReference.id} AND l.asset_id=a.id)
                OR EXISTS (SELECT 1 FROM fams_scrap_request s WHERE s.id=#{filters.businessReference.id} AND s.asset_id=a.id)
            )
            """;

    private AssetLedgerWorkflowSql() {}
}
