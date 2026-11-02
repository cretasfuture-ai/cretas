-- S6 follow-up (2026-11-03): read intents must not be stricter than the App page that serves the same data.
--
-- These read intents still carry January-era required_roles lists (old role model: no quality_manager,
-- no production_manager, ...). With no required_permission set, IntentPermissionGate falls back to them, so e.g.
-- quality_manager asking 「追溯批次」 got NO_PERMISSION while /traceability/full and /impact answer them in the App.
--
-- Each listed intent binds a READ tool whose own gate GatedDataToolAiPathPermissionTest pins to the App's read
-- endpoints for the same data (no more, no less), so the intent-level role list is dropped and the tool decides.
-- Write intents keep their lists (who may write is a separate ruling). Rows with a required_permission are untouched.
-- The (intent_code, tool_name) pairs are checked against the tools' access mode by IntentRequiredRolesCleanupContractTest.
UPDATE ai_intent_configs
SET required_roles = NULL,
    updated_at = NOW()
WHERE deleted_at IS NULL
  AND (required_permission IS NULL OR btrim(required_permission) = '')
  AND (intent_code, tool_name) IN (
    ('ALERT_ACTIVE', 'alert_active'),
    ('ALERT_BY_EQUIPMENT', 'alert_by_equipment'),
    ('ALERT_DIAGNOSE', 'alert_diagnose'),
    ('ALERT_LIST', 'alert_list'),
    ('ALERT_STATS', 'alert_stats'),
    ('ALERT_TRIAGE', 'alert_triage'),
    ('ATTENDANCE_ANOMALY', 'attendance_anomaly'),
    ('ATTENDANCE_DEPARTMENT', 'attendance_department'),
    ('ATTENDANCE_HISTORY', 'attendance_history'),
    ('ATTENDANCE_MONTHLY', 'attendance_monthly'),
    ('ATTENDANCE_STATS', 'attendance_stats'),
    ('ATTENDANCE_STATS_BY_DEPT', 'attendance_stats_by_dept'),
    ('ATTENDANCE_STATUS', 'attendance_status'),
    ('ATTENDANCE_TODAY', 'attendance_today'),
    ('CUSTOMER_ACTIVE', 'customer_active'),
    ('CUSTOMER_BY_TYPE', 'customer_by_type'),
    ('CUSTOMER_LIST', 'customer_list'),
    ('CUSTOMER_PURCHASE_HISTORY', 'customer_purchase_history'),
    ('CUSTOMER_SEARCH', 'customer_search'),
    ('CUSTOMER_STATS', 'customer_stats'),
    ('EQUIPMENT_MAINTENANCE', 'equipment_maintenance'),
    ('MATERIAL_BATCH_QUERY', 'material_batch_query'),
    ('MATERIAL_EXPIRED_QUERY', 'material_expired_query'),
    ('MATERIAL_EXPIRING_ALERT', 'material_expiring_alert'),
    ('MATERIAL_FIFO_RECOMMEND', 'material_fifo_recommend'),
    ('MATERIAL_LOW_STOCK_ALERT', 'material_low_stock_alert'),
    ('ORDER_LIST', 'order_list'),
    ('ORDER_STATUS', 'order_query'),
    ('ORDER_TODAY', 'order_today'),
    ('QUALITY_CHECK_QUERY', 'quality_check_query'),
    ('QUALITY_CRITICAL_ITEMS', 'quality_critical_items'),
    ('QUALITY_DISPOSITION_EVALUATE', 'quality_disposition_evaluate'),
    ('QUALITY_STATS', 'quality_stats_query'),
    ('REPORT_ANOMALY', 'report_anomaly'),
    ('REPORT_DASHBOARD_OVERVIEW', 'report_dashboard_overview'),
    ('REPORT_EFFICIENCY', 'report_efficiency'),
    ('REPORT_FINANCE', 'report_finance'),
    ('REPORT_INVENTORY', 'report_inventory'),
    ('REPORT_KPI', 'report_kpi'),
    ('REPORT_PRODUCTION', 'report_production'),
    ('REPORT_QUALITY', 'report_quality'),
    ('REPORT_TRENDS', 'report_trends'),
    ('SCALE_DEVICE_DETAIL', 'scale_device_detail'),
    ('SCALE_LIST_DEVICES', 'scale_list_devices'),
    ('SCHEDULING_LIST', 'scheduling_list'),
    ('SHIPMENT_QUERY', 'shipment_query'),
    ('SHIPMENT_STATS', 'shipment_stats_query'),
    ('SUPPLIER_ACTIVE', 'supplier_active'),
    ('SUPPLIER_BY_CATEGORY', 'supplier_by_category'),
    ('SUPPLIER_LIST', 'supplier_list'),
    ('SUPPLIER_RANKING', 'supplier_ranking'),
    ('SUPPLIER_SEARCH', 'supplier_search'),
    ('TRACE_BATCH', 'trace_batch'),
    ('TRACE_FULL', 'trace_full'),
    ('TRACE_PUBLIC', 'trace_public')
  );
