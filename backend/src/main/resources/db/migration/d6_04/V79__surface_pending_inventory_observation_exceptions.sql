-- Migration time is when legacy observations first enter the message center.
INSERT INTO fams_todo (pool_type,business_type,business_id,todo_type,todo_status,opened_at)
SELECT 'ASSET_ADMIN','INVENTORY_OBSERVATION',o.id,
       CASE o.outcome WHEN 'CONFLICT_REVIEW' THEN 'INVENTORY_CONFLICT' ELSE 'INVENTORY_DIFFERENCE' END,
       'OPEN', UTC_TIMESTAMP(3)
FROM fams_inventory_task t
JOIN fams_inventory_observation o ON o.task_id=t.id
JOIN fams_inventory_item i ON i.id=o.item_id AND i.recheck_generation=o.recheck_generation
LEFT JOIN fams_inventory_item_review r ON r.item_id=i.id AND r.recheck_generation=o.recheck_generation
WHERE t.task_status IN ('IN_PROGRESS','WAITING_REVIEW')
  AND o.outcome IN ('CONFLICT_REVIEW','DIFFERENCE_REVIEW')
  AND (r.review_status IS NULL OR r.review_status<>'ACCEPTED')
  AND NOT EXISTS (
      SELECT 1 FROM fams_todo todo
      WHERE todo.business_type='INVENTORY_OBSERVATION' AND todo.business_id=o.id
  );
