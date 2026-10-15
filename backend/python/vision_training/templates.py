"""Versioned task template catalog.

The catalog is intentionally finite.  Adding a factory or task family requires
an explicit template registration; an arbitrary client string cannot select a
training pipeline.
"""

from __future__ import annotations

from typing import Iterable

from .errors import UnknownTaskTemplateError
from .models import StageName, TaskTemplate

SOP_VERSION = "vision-training-sop-v1"


def _template(
    *,
    template_id: str,
    task_family: str,
    title: str,
    scope: str,
    required_fields: tuple[str, ...],
    human_standard: str,
    independent_acceptance: str,
    adapter_id: str | None = None,
    ocr_rules_required: bool = False,
) -> TaskTemplate:
    return TaskTemplate(
        template_id=template_id,
        version="v1",
        task_family=task_family,
        title=title,
        scope=scope,  # type: ignore[arg-type]
        required_fields=required_fields,
        required_receipts=("dataset", "model", "evaluation"),
        stages=(
            StageName.PREVIEW,
            StageName.COLLECT,
            StageName.DATASET,
            StageName.TRAIN,
            StageName.EVALUATE,
            StageName.INDEPENDENT_ACCEPTANCE,
            StageName.ENABLE,
        ),
        ocr_rules_required=ocr_rules_required,
        adapter_id=adapter_id,
        human_standard=human_standard,
        independent_acceptance=independent_acceptance,
    )


COMMON_FIELDS = (
    "factory_id",
    "workstation_id",
    "task_family",
    "product_scope",
    "packaging_scope",
    "data_version",
    "recipe_version",
    "human_standard_version",
    "independent_acceptance_version",
)

BUILTIN_TEMPLATES: tuple[TaskTemplate, ...] = (
    _template(
        template_id="generic-packaging-detection",
        task_family="packaging-detection",
        title="Generic packaging detection",
        scope="generic",
        required_fields=COMMON_FIELDS,
        human_standard="human-reviewed labels with an immutable source manifest",
        independent_acceptance="factory-owned holdout with a separate acceptance receipt",
    ),
    _template(
        template_id="generic-packaging-ocr",
        task_family="packaging-ocr",
        title="Generic packaging OCR",
        scope="generic",
        required_fields=COMMON_FIELDS + ("ocr_rules_version",),
        ocr_rules_required=True,
        human_standard="human-reviewed text regions and an OCR rules receipt",
        independent_acceptance="factory-owned blind OCR set with a separate acceptance receipt",
    ),
    _template(
        template_id="liushanmen-tray",
        task_family="liushanmen-tray-label-detection",
        title="六膳门 tray label adapter",
        scope="specialized",
        required_fields=COMMON_FIELDS,
        adapter_id="liushanmen-visionlab-v1",
        human_standard="the existing VisionLab human-reviewed receipt contract",
        independent_acceptance="the existing VisionLab protected holdout and independent normal-set gates",
    ),
)


class TemplateCatalog:
    def __init__(self, templates: Iterable[TaskTemplate] = BUILTIN_TEMPLATES) -> None:
        self._templates = {template.template_id: template for template in templates}
        self._by_family = {template.task_family: template for template in templates}

    def get(self, template_id: str) -> TaskTemplate:
        try:
            return self._templates[template_id]
        except KeyError as exc:
            raise UnknownTaskTemplateError(f"unknown task template: {template_id}") from exc

    def for_task_family(self, task_family: str) -> TaskTemplate:
        try:
            return self._by_family[task_family]
        except KeyError as exc:
            raise UnknownTaskTemplateError(
                f"no registered SOP template for task family: {task_family}"
            ) from exc

    def register(self, template: TaskTemplate) -> None:
        if template.template_id in self._templates:
            raise ValueError(f"template already registered: {template.template_id}")
        if template.task_family in self._by_family:
            raise ValueError(f"task family already registered: {template.task_family}")
        self._templates[template.template_id] = template
        self._by_family[template.task_family] = template

    def all(self) -> tuple[TaskTemplate, ...]:
        return tuple(self._templates.values())
