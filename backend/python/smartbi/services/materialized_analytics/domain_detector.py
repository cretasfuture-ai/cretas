"""Factory-safe domain detection for materialized uploads.

Historical ``Domain.RESTAURANT`` values remain readable, but new uploads never
enter that retired domain or its templates.
"""
from __future__ import annotations

from typing import List, Protocol, runtime_checkable

from .schema import Domain, Field


@runtime_checkable
class DomainDetector(Protocol):
    def detect(self, fields: List[Field], sample_data: List[dict]) -> Domain: ...


class FactorySafeDetector:
    """Return UNKNOWN until a non-retired factory scorer is available."""

    def detect(self, fields: List[Field], sample_data: List[dict]) -> Domain:
        return Domain.UNKNOWN


def get_default_detector() -> DomainDetector:
    return FactorySafeDetector()
