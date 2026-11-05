from __future__ import annotations

"""Finance module — margin spec and shrinkage engine.

Part of P3.5 general capabilities refactor. Modules added incrementally:
  - margin_spec (P3.5A QW3): boundary config
  - shrinkage_engine (P3.5C B3): standard vs actual variance
"""
from .margin_spec import MarginSpec, StoredValueTreatment, MarginCalcMode
from .shrinkage_engine import (
    ActionItem,
    ShrinkageEngine,
    ShrinkageReport,
    ShrinkageRow,
)

__all__ = [
    "MarginSpec",
    "StoredValueTreatment",
    "MarginCalcMode",
    "ActionItem",
    "ShrinkageEngine",
    "ShrinkageReport",
    "ShrinkageRow",
]
