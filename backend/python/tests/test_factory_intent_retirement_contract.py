"""Contract tests for the reviewed factory intent retirement boundary."""
import importlib
import json
import sys
from pathlib import Path

import pytest

from ai import factory_catalog as catalog
from ai import db, registry

FINETUNE = Path(__file__).resolve().parents[3] / "scripts" / "finetune"
if str(FINETUNE) not in sys.path:
    sys.path.insert(0, str(FINETUNE))


def test_reviewed_catalog_counts_and_alias_are_stable():
    cat = catalog.load_catalog()
    assert len(cat["canonical"]) == 266
    assert len(cat["retired"]) == 81
    assert len(cat["retired_tools"]) == 56
    assert len(cat["reclassified"]) == 29
    assert catalog.canonical_code("EFFICIENCY_COMPARE_ALIAS", cat) == "EFFICIENCY_COMPARISON"
    assert catalog.canonical_code("FACTORY_TENANT_CUSTOM", cat) == "FACTORY_TENANT_CUSTOM"


def test_packaged_catalog_matches_java_source_snapshot():
    assert catalog.PACKAGED_CATALOG_PATH.read_bytes() == catalog.JAVA_CATALOG_PATH.read_bytes()


def test_generated_migration_does_not_accidentally_open_a_flyway_placeholder():
    root = Path(__file__).resolve().parents[3]
    spec = importlib.util.spec_from_file_location(
        "factory_catalog_migration_generator", root / "scripts/ai/generate_factory_intent_migration.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    rendered = module.render()
    assert "${" not in rendered
    assert "$factory_catalog$\n{" in rendered
    assert module.TARGET.read_text(encoding="utf-8") == rendered


@pytest.mark.parametrize("retained", sorted(catalog.load_catalog()["reclassified"]))
def test_retired_and_restaurant_rows_are_rejected_but_custom_rows_survive(retained):
    cat = catalog.load_catalog()
    rows = [
        {"intent_code": next(iter(cat["retired"])), "factory_id": None, "business_type": "FACTORY"},
        {"intent_code": "RESTAURANT_CUSTOM", "factory_id": "F1", "business_type": "FACTORY"},
        {"intent_code": "FACTORY_TENANT_CUSTOM", "factory_id": "F1", "business_type": "FACTORY"},
        {"intent_code": "EFFICIENCY_COMPARE_ALIAS", "factory_id": None, "business_type": "FACTORY"},
        {"intent_code": "EFFICIENCY_COMPARISON", "factory_id": None, "business_type": "FACTORY",
         "required_roles": ["factory_admin"]},
        {"intent_code": retained, "factory_id": None, "business_type": "FACTORY"},
    ]
    out = catalog.filter_runtime_rows(rows, cat)
    codes = {row["intent_code"] for row in out}
    assert next(iter(cat["retired"])) not in codes
    assert "RESTAURANT_CUSTOM" not in codes
    assert "FACTORY_TENANT_CUSTOM" in codes
    assert "EFFICIENCY_COMPARISON" in codes
    assert catalog.canonical_code(retained, cat) in codes


def test_db_visibility_preserves_tenant_and_active_delete_guards():
    cat = catalog.load_catalog()
    retired = next(iter(cat["retired"]))
    rows = [
        {"intent_code": retired, "factory_id": "F1", "business_type": "FACTORY", "is_active": True},
        {"intent_code": "FACTORY_TENANT_CUSTOM", "factory_id": "F1", "business_type": "FACTORY", "is_active": True},
        {"intent_code": "OTHER_TENANT", "factory_id": "F2", "business_type": "FACTORY", "is_active": True},
        {"intent_code": "DELETED", "factory_id": "F1", "business_type": "FACTORY", "is_active": True, "deleted_at": "x"},
    ]
    out = db.filter_intents_for_request(rows, "F1", "FACTORY")
    assert [row["intent_code"] for row in out] == ["FACTORY_TENANT_CUSTOM"]


@pytest.mark.parametrize(
    "shadow",
    [
        {"is_active": False, "deleted_at": None},
        {"is_active": True, "deleted_at": "2026-11-04T00:00:00"},
    ],
)
def test_inactive_or_deleted_tenant_shadow_blocks_platform_fallback(shadow):
    rows = [
        {
            "intent_code": "FACTORY_PLATFORM_DEFAULT",
            "factory_id": None,
            "business_type": "FACTORY",
            "is_active": True,
            "deleted_at": None,
        },
        {
            "intent_code": "FACTORY_PLATFORM_DEFAULT",
            "factory_id": "F1",
            "business_type": "FACTORY",
            **shadow,
        },
    ]

    assert db.filter_intents_for_request(rows, "F1", "FACTORY") == []
    assert [
        row["intent_code"]
        for row in db.filter_intents_for_request(rows, "F2", "FACTORY")
    ] == ["FACTORY_PLATFORM_DEFAULT"]


def test_db_native_blocked_code_projection_is_tenant_scoped():
    sql = db.BLOCKED_CODES_SQL.upper()
    assert "FACTORY_ID IS NOT NULL" in sql
    assert "DELETED_AT IS NOT NULL" in sql
    assert "IS_ACTIVE IS DISTINCT FROM TRUE" in sql


def test_reclassified_restaurant_source_is_projected_before_factory_filter():
    cat = catalog.load_catalog()
    code = "COLD_CHAIN_EQUIPMENT_REGISTER"
    assert code in cat["reclassified"]
    out = db.filter_intents_for_request(
        [{"intent_code": code, "factory_id": "F1", "business_type": "RESTAURANT",
          "is_active": True, "required_roles": ["factory_operator"]}],
        "F1", "FACTORY",
    )
    assert len(out) == 1
    assert out[0]["intent_code"] == code
    assert out[0]["business_type"] == "FACTORY"
    assert out[0]["factory_id"] == "F1"
    assert out[0]["required_roles"] == ["factory_operator"]
    assert out[0]["tool_name"] == cat["canonical"][code]["tool_name"]


def test_skill_bound_reclassified_row_clears_legacy_tool_binding():
    cat = catalog.load_catalog()
    code = "ADDITIVE_COMPLIANCE"
    assert code in cat["reclassified"]
    assert cat["canonical"][code]["tool_name"] is None
    row = {
        "intent_code": code,
        "factory_id": "F1",
        "business_type": "RESTAURANT",
        "tool_name": "legacy_restaurant_tool",
        "required_roles": ["factory_operator"],
        "requires_approval": True,
        "is_active": True,
        "deleted_at": None,
    }
    projected = catalog.project_reclassified_row(row, cat)
    assert projected["tool_name"] is None
    assert projected["business_type"] == "FACTORY"
    assert projected["required_roles"] == row["required_roles"]
    assert projected["requires_approval"] is True
    assert projected["factory_id"] == "F1"


def test_pending_shipment_binding_override_replaces_legacy_confirm_tool():
    cat = catalog.load_catalog()
    code = "SPRINT10_SHIPMENT_PENDING_TODAY"
    row = {
        "intent_code": code,
        "factory_id": "F1",
        "business_type": "COMMON",
        "tool_name": "shipment_confirm_create",
        "required_roles": ["factory_operator"],
        "requires_approval": False,
        "is_active": True,
        "deleted_at": None,
    }
    projected = catalog.project_reclassified_row(row, cat)
    assert projected["tool_name"] == "shipment_pending_today"
    assert projected["required_roles"] == row["required_roles"]
    assert projected["requires_approval"] is False
    assert projected["factory_id"] == "F1"


def test_registry_filters_retired_phrases_and_normalizes_alias():
    cat = catalog.load_catalog()
    retired = next(iter(cat["retired"]))
    snap = registry.build_snapshot(
        [
            {"factory_id": "F1", "business_type": "FACTORY", "normalized_text": "bad", "intent_code": retired},
            {"factory_id": "F1", "business_type": "FACTORY", "normalized_text": "eff", "intent_code": "EFFICIENCY_COMPARE_ALIAS"},
        ],
        [],
    )
    assert "bad" not in snap.phrases[("F1", "FACTORY")]
    assert registry.lookup(snap, "eff", "F1", "FACTORY")[0] == "EFFICIENCY_COMPARISON"


def test_alias_only_row_is_not_allowed_to_inherit_canonical_visibility():
    alias_only = catalog.filter_runtime_rows([{
        "intent_code": "EFFICIENCY_COMPARE_ALIAS", "factory_id": "F1",
        "business_type": "FACTORY", "required_roles": [], "requires_approval": False,
    }])
    assert alias_only == []
    strong_target = catalog.filter_runtime_rows([
        {"intent_code": "EFFICIENCY_COMPARE_ALIAS", "factory_id": "F1", "business_type": "COMMON",
         "required_roles": [], "requires_approval": False},
        {"intent_code": "EFFICIENCY_COMPARISON", "factory_id": "F1", "business_type": "COMMON",
         "required_roles": ["factory_admin"], "requires_approval": True},
    ])
    assert len(strong_target) == 1
    assert strong_target[0]["intent_code"] == "EFFICIENCY_COMPARISON"
    assert strong_target[0]["required_roles"] == ["factory_admin"]
    assert strong_target[0]["requires_approval"] is True


def test_training_label_contract_rejects_retired_without_resizing_head(tmp_path):
    contract = importlib.import_module("training_label_contract")
    cat = catalog.load_catalog()
    retired = next(iter(cat["retired"]))
    mapping = {"KEEP": 0, retired: 1}
    (tmp_path / "label_mapping.json").write_text(json.dumps({"label_to_id": mapping}), encoding="utf-8")
    (tmp_path / "config.json").write_text(json.dumps({"num_labels": 2}), encoding="utf-8")
    with pytest.raises(ValueError, match="Retired intent"):
        contract.resolve_mapping(tmp_path, None, [{"label": retired}])
    assert json.loads((tmp_path / "label_mapping.json").read_text(encoding="utf-8"))["label_to_id"] == mapping
