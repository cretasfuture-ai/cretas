"""Shared factory intent retirement/catalog policy.

The Java resource is the reviewed source of truth for the platform catalog.
This module deliberately does not import the database, a model, or FastAPI so
runtime filtering and offline training use the same small policy surface.

The catalog is *not* a tenant allow-list: factory-owned, non-retired custom
intent rows remain visible after the normal tenant/active/mode checks.
"""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional


PACKAGED_CATALOG_PATH = (
    Path(__file__).resolve().parent
    / "data"
    / "factory-intent-catalog.json"
)
# Kept as a named compatibility constant for callers that need to report the
# packaged source; runtime loading intentionally never falls back to Java
# source files which may not be present in a Python-only deployment.
CATALOG_PATH = PACKAGED_CATALOG_PATH
JAVA_CATALOG_PATH = (
    Path(__file__).resolve().parents[3]
    / "backend"
    / "java"
    / "cretas-api"
    / "src"
    / "main"
    / "resources"
    / "ai"
    / "factory-intent-catalog.json"
)


class FactoryCatalogError(ValueError):
    """Raised when the reviewed catalog is malformed or cannot be read."""


def _validate(raw: Mapping[str, Any]) -> Dict[str, Any]:
    if not isinstance(raw, Mapping) or raw.get("schema_version") != 1:
        raise FactoryCatalogError("factory intent catalog schema_version must be 1")
    rows = raw.get("canonical_intents")
    if not isinstance(rows, list):
        raise FactoryCatalogError("canonical_intents must be a list")
    canonical: Dict[str, Dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, Mapping) or not isinstance(row.get("intent_code"), str):
            raise FactoryCatalogError("canonical intent rows require intent_code")
        code = row["intent_code"].strip()
        if not code or code in canonical:
            raise FactoryCatalogError(f"duplicate/empty canonical intent: {code!r}")
        canonical[code] = dict(row)
    retired = frozenset(str(x).strip() for x in raw.get("retired_intents", []) if str(x).strip())
    retired_tools = frozenset(str(x).strip().casefold() for x in raw.get("retired_tools", []) if str(x).strip())
    aliases_raw = raw.get("aliases", raw.get("confirmed_aliases", {}))
    if not isinstance(aliases_raw, Mapping):
        raise FactoryCatalogError("aliases must be an object")
    aliases = {str(k).strip(): str(v).strip() for k, v in aliases_raw.items()}
    for source, target in aliases.items():
        if not source or not target or target not in canonical or target in retired:
            raise FactoryCatalogError(f"invalid intent alias: {source!r} -> {target!r}")
    return {
        "schema_version": 1,
        "canonical": canonical,
        "retired": retired,
        "retired_tools": retired_tools,
        "aliases": aliases,
        "reclassified": frozenset(str(x).strip() for x in raw.get("reclassified_intents", []) if str(x).strip()),
    }


def load_catalog(path: Optional[Path | str] = None) -> Dict[str, Any]:
    """Read and validate the reviewed catalog without DB/model dependencies."""
    target = Path(path) if path is not None else PACKAGED_CATALOG_PATH
    try:
        raw = json.loads(target.read_text(encoding="utf-8-sig"))
    except OSError as exc:
        raise FactoryCatalogError(f"factory intent catalog unavailable: {target}") from exc
    except json.JSONDecodeError as exc:
        raise FactoryCatalogError(f"factory intent catalog is invalid JSON: {target}") from exc
    return _validate(raw)


@lru_cache(maxsize=1)
def get_catalog() -> Dict[str, Any]:
    return load_catalog()


def clear_catalog_cache() -> None:
    get_catalog.cache_clear()


def canonical_code(code: Any, catalog: Optional[Mapping[str, Any]] = None) -> Optional[str]:
    """Return the reviewed canonical code; unknown tenant codes are preserved."""
    if code is None:
        return None
    value = str(code).strip()
    if not value:
        return None
    cat = catalog or get_catalog()
    return str(cat["aliases"].get(value, value))


def is_retired(code: Any, catalog: Optional[Mapping[str, Any]] = None) -> bool:
    value = canonical_code(code, catalog)
    if value is None:
        return False
    cat = catalog or get_catalog()
    original = str(code).strip() if code is not None else ""
    return original in cat["retired"] or value in cat["retired"] or value.startswith("RESTAURANT_")


def is_retired_tool(tool_name: Any, catalog: Optional[Mapping[str, Any]] = None) -> bool:
    if tool_name is None:
        return False
    tool = str(tool_name).strip().casefold()
    if not tool:
        return False
    cat = catalog or get_catalog()
    return tool in cat["retired_tools"] or tool.startswith("restaurant_")


def is_platform_intent(code: Any, catalog: Optional[Mapping[str, Any]] = None) -> bool:
    value = canonical_code(code, catalog)
    return value is not None and value in (catalog or get_catalog())["canonical"]


def canonicalize_row(row: Mapping[str, Any], catalog: Optional[Mapping[str, Any]] = None) -> Dict[str, Any]:
    """Copy a visible DB row and normalize only the confirmed alias field."""
    result = dict(row)
    normalized = canonical_code(result.get("intent_code"), catalog)
    if normalized is not None:
        result["intent_code"] = normalized
    return result


def project_reclassified_row(
    row: Mapping[str, Any], catalog: Optional[Mapping[str, Any]] = None,
) -> Dict[str, Any]:
    """Project reviewed reclassified metadata without changing tenant guards.

    Java may still expose a legacy RESTAURANT row for one of the 29 retained
    capabilities. Only the reviewed business type/tool binding is projected;
    factory_id, active/deleted state, permissions and all other row fields stay
    owned by the DB row.
    """
    cat = catalog or get_catalog()
    result = dict(row)
    raw_code = str(row.get("intent_code") or "").strip()
    code = canonical_code(raw_code, cat)
    canonical = cat["canonical"].get(code)
    if canonical is not None and (
        code in cat["reclassified"] or canonical.get("binding_override") is True
    ):
        if code in cat["reclassified"]:
            result["business_type"] = canonical["business_type"]
        # A reviewed skill-bound row may deliberately have no direct tool.
        # Explicitly overwrite the legacy DB value, including with None.
        result["tool_name"] = canonical.get("tool_name")
        result["intent_code"] = code
    return result


def filter_runtime_rows(
    rows: Iterable[Mapping[str, Any]],
    catalog: Optional[Mapping[str, Any]] = None,
) -> List[Dict[str, Any]]:
    """Remove retired/catalogued restaurant rows, preserving tenant customs."""
    cat = catalog or get_catalog()
    source_rows = [project_reclassified_row(row, cat) for row in rows]
    canonical_scopes = {
        (
            str(row.get("intent_code") or "").strip(),
            row.get("factory_id"),
            row.get("business_type"),
        )
        for row in source_rows
        if row.get("intent_code")
        and str(row.get("intent_code")).strip() not in cat["aliases"]
    }
    result: List[Dict[str, Any]] = []
    seen = set()
    for row in source_rows:
        code = row.get("intent_code")
        if is_retired(code, cat) or is_retired_tool(row.get("tool_name"), cat):
            continue
        raw_code = str(code or "").strip()
        normalized = canonical_code(code, cat)
        if raw_code in cat["aliases"]:
            scope = (normalized, row.get("factory_id"), row.get("business_type"))
            if scope not in canonical_scopes:
                # An alias is not a fallback configuration. Without the real
                # canonical row it cannot inherit permissions/confirmation.
                continue
            # The target row is authoritative for permissions, approval and
            # tenant scope. Keep the alias out of the visible list entirely.
            continue
        normalized = canonicalize_row(row, cat)
        key = (normalized.get("intent_code"), normalized.get("factory_id"), normalized.get("business_type"))
        if key in seen:
            continue
        seen.add(key)
        result.append(normalized)
    return result


def normalize_training_label(
    label: Any,
    catalog: Optional[Mapping[str, Any]] = None,
    *,
    require_canonical: bool = False,
) -> str:
    """Normalize a confirmed alias and reject retired/restaurant labels."""
    if not isinstance(label, str) or not label.strip():
        raise ValueError("Training label must be a nonempty string")
    cat = catalog or get_catalog()
    value = canonical_code(label, cat)
    if is_retired(label, cat) or is_retired_tool(label, cat):
        raise ValueError(f"Retired intent cannot be used for training: {label}")
    if require_canonical:
        row = cat["canonical"].get(value)
        if row is None:
            raise ValueError(f"Unknown intent cannot be used for training: {label}")
        if row.get("is_active", True) is False:
            raise ValueError(f"Inactive intent cannot be used for training: {label}")
    return value  # type: ignore[return-value]


def training_catalog_labels(catalog: Optional[Mapping[str, Any]] = None) -> List[str]:
    """Return the independent reviewed platform label directory (normally 265)."""
    cat = catalog or get_catalog()
    return sorted(
        code for code, row in cat["canonical"].items()
        if row.get("is_active", True) is not False and not is_retired(code, cat)
    )


__all__ = [
    "CATALOG_PATH", "PACKAGED_CATALOG_PATH", "JAVA_CATALOG_PATH", "FactoryCatalogError", "load_catalog", "get_catalog", "clear_catalog_cache",
    "canonical_code", "is_retired", "is_retired_tool", "is_platform_intent", "canonicalize_row",
    "project_reclassified_row", "filter_runtime_rows", "normalize_training_label", "training_catalog_labels",
]
