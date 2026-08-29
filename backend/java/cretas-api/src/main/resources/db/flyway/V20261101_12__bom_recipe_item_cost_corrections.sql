CREATE TABLE IF NOT EXISTS bom_recipe_item_cost_corrections (
    id                 VARCHAR(191) PRIMARY KEY,
    factory_id         VARCHAR(50) NOT NULL,
    bom_recipe_id      VARCHAR(191) NOT NULL,
    bom_recipe_item_id BIGINT NOT NULL,
    material_type_id   VARCHAR(191) NOT NULL,
    old_unit_price     NUMERIC(15,4),
    new_unit_price     NUMERIC(15,4) NOT NULL,
    old_item_cost      NUMERIC(15,4),
    new_item_cost      NUMERIC(15,4) NOT NULL,
    reason             VARCHAR(255) NOT NULL,
    corrected_by       BIGINT NOT NULL,
    corrected_by_role  VARCHAR(50) NOT NULL,
    corrected_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bricc_recipe FOREIGN KEY (bom_recipe_id) REFERENCES bom_recipes(id),
    CONSTRAINT fk_bricc_item FOREIGN KEY (bom_recipe_item_id) REFERENCES bom_recipe_items(id),
    CONSTRAINT fk_bricc_material FOREIGN KEY (material_type_id) REFERENCES raw_material_types(id),
    CONSTRAINT fk_bricc_user FOREIGN KEY (corrected_by) REFERENCES users(id),
    CONSTRAINT chk_bricc_positive_price CHECK (new_unit_price > 0),
    CONSTRAINT chk_bricc_nonnegative_cost CHECK (new_item_cost >= 0),
    CONSTRAINT chk_bricc_changed CHECK (
        old_unit_price IS DISTINCT FROM new_unit_price
        OR old_item_cost IS DISTINCT FROM new_item_cost
    )
);

CREATE INDEX IF NOT EXISTS idx_bricc_item
    ON bom_recipe_item_cost_corrections(bom_recipe_item_id, corrected_at DESC);
CREATE INDEX IF NOT EXISTS idx_bricc_factory
    ON bom_recipe_item_cost_corrections(factory_id, corrected_at DESC);
