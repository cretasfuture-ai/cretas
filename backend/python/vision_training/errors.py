"""Errors raised by the factory-scoped vision training coordinator."""

from __future__ import annotations


class VisionTrainingError(Exception):
    """Base class for errors that are safe to expose to the API layer."""


class UnknownTaskTemplateError(VisionTrainingError):
    """The requested task family has no explicitly registered SOP template."""


class InvalidTaskSpecError(VisionTrainingError):
    """A project specification is incomplete or incompatible with its template."""


class ScopeViolationError(VisionTrainingError):
    """An asset or factory context does not match the project scope."""


class PreviewMismatchError(VisionTrainingError):
    """The explicit save request does not match the preview shown to the user."""


class DraftNotFoundError(VisionTrainingError):
    """A requested draft is not present in the injected store."""


class AdapterNotRegisteredError(VisionTrainingError):
    """A specialized task has no explicitly registered stage adapter."""
