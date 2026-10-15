-- Draft actor namespace. Existing rows remain NULL/NULL because historical actor type is unknown.
ALTER TABLE alert_policy_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE alert_policy_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE alert_policy_drafts ADD CONSTRAINT ck_asm_alert_policy_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE allergen_label_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE allergen_label_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE allergen_label_drafts ADD CONSTRAINT ck_asm_allergen_label_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE contract_material_policy_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE contract_material_policy_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE contract_material_policy_drafts ADD CONSTRAINT ck_asm_contract_material_policy_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE critical_control_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE critical_control_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE critical_control_drafts ADD CONSTRAINT ck_asm_critical_control_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE deviation_capa_template_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE deviation_capa_template_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE deviation_capa_template_drafts ADD CONSTRAINT ck_asm_deviation_capa_template_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE emergency_defense_plan_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE emergency_defense_plan_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE emergency_defense_plan_drafts ADD CONSTRAINT ck_asm_emergency_defense_plan_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE environmental_monitoring_template_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE environmental_monitoring_template_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE environmental_monitoring_template_drafts ADD CONSTRAINT ck_asm_environmental_monitoring_template_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE equipment_plan_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE equipment_plan_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE equipment_plan_drafts ADD CONSTRAINT ck_asm_equipment_plan_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE form_schema_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE form_schema_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE form_schema_drafts ADD CONSTRAINT ck_asm_form_schema_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE hazard_analysis_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE hazard_analysis_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE hazard_analysis_drafts ADD CONSTRAINT ck_asm_hazard_analysis_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE hygienic_zone_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE hygienic_zone_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE hygienic_zone_drafts ADD CONSTRAINT ck_asm_hygienic_zone_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE material_type_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE material_type_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE material_type_drafts ADD CONSTRAINT ck_asm_material_type_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE module_config_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE module_config_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE module_config_drafts ADD CONSTRAINT ck_asm_module_config_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE packaging_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE packaging_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE packaging_drafts ADD CONSTRAINT ck_asm_packaging_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE product_type_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE product_type_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE product_type_drafts ADD CONSTRAINT ck_asm_product_type_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE quality_release_template_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE quality_release_template_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE quality_release_template_drafts ADD CONSTRAINT ck_asm_quality_release_template_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE role_training_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE role_training_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE role_training_drafts ADD CONSTRAINT ck_asm_role_training_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE sanitation_cip_template_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE sanitation_cip_template_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE sanitation_cip_template_drafts ADD CONSTRAINT ck_asm_sanitation_cip_template_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE supplier_receiving_policy_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE supplier_receiving_policy_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE supplier_receiving_policy_drafts ADD CONSTRAINT ck_asm_supplier_receiving_policy_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE trace_recall_plan_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE trace_recall_plan_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE trace_recall_plan_drafts ADD CONSTRAINT ck_asm_trace_recall_plan_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE warehouse_control_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE warehouse_control_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE warehouse_control_drafts ADD CONSTRAINT ck_asm_warehouse_control_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

ALTER TABLE work_process_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE work_process_drafts ADD COLUMN actor_type VARCHAR(32), ADD COLUMN actor_id BIGINT;
ALTER TABLE work_process_drafts ADD CONSTRAINT ck_asm_work_process_drafts_actor CHECK (
    (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
    OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
        AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
            OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
);

