"""Read trusted, code-external recipe/worker registrations; never provision them.

An empty registration exposes planning only. Customer paths and worker secrets
are not accepted from the browser or stored in a checked-in configuration.
"""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
import hmac
import os
from pathlib import Path
import re

from pydantic import Field, field_validator, model_validator

from .models import RecipeAvailability, StrictModel
from .router import WorkerBinding
from .templates import SOP_VERSION, TemplateCatalog

_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}")
_ENV_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,100}")


class WorkerRegistrationConfig(StrictModel):
    worker_id: str
    token_env: str
    factory_ids: tuple[str, ...] = Field(min_length=1)
    project_ids: tuple[str, ...] = ()

    @field_validator("worker_id")
    @classmethod
    def _worker_id(cls, value: str) -> str:
        if not _IDENTIFIER.fullmatch(value):
            raise ValueError("worker_id must be a named identifier")
        return value

    @field_validator("token_env")
    @classmethod
    def _token_env(cls, value: str) -> str:
        if not _ENV_NAME.fullmatch(value):
            raise ValueError("token_env must name an environment variable")
        return value

    @field_validator("factory_ids", "project_ids")
    @classmethod
    def _scope(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if len(values) != len(set(values)) or any(not _IDENTIFIER.fullmatch(value) for value in values):
            raise ValueError("worker scope requires unique named identifiers")
        return values


class ServerRegistrationConfig(StrictModel):
    recipes: tuple[RecipeAvailability, ...] = ()
    worker_bindings: tuple[WorkerRegistrationConfig, ...] = ()
    specialized_factory_allowlist: dict[str, tuple[str, ...]] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _validate_bindings(self) -> "ServerRegistrationConfig":
        catalog = TemplateCatalog()
        worker_ids = [worker.worker_id for worker in self.worker_bindings]
        if len(worker_ids) != len(set(worker_ids)):
            raise ValueError("duplicate worker_id")
        recipe_keys = [(recipe.factory_id, recipe.recipe_id) for recipe in self.recipes]
        if len(recipe_keys) != len(set(recipe_keys)):
            raise ValueError("duplicate factory/recipe binding")
        for template_id, factories in self.specialized_factory_allowlist.items():
            template = catalog.get(template_id)
            if template.scope != "specialized":
                raise ValueError("only specialized templates can have a factory allowlist")
            if not factories or len(factories) != len(set(factories)) or any(not _IDENTIFIER.fullmatch(value) for value in factories):
                raise ValueError("specialized allowlist requires unique factory identifiers")
        for recipe in self.recipes:
            if not _IDENTIFIER.fullmatch(recipe.factory_id) or not _IDENTIFIER.fullmatch(recipe.recipe_id):
                raise ValueError("recipe scope requires named identifiers")
            if bool(recipe.preparation_project_id) != bool(recipe.preparation_sha256):
                raise ValueError("photo preparation registration requires both source project and snapshot hash")
            if recipe.preparation_sha256 and (recipe.data_version != "prep-" + recipe.preparation_sha256
                    or "dataset" not in recipe.stage_input_hashes):
                raise ValueError("prepared recipe must bind the frozen data version and dataset input")
            if recipe.available and recipe.preparation_project_id and not any(
                    recipe.factory_id in worker.factory_ids and recipe.preparation_project_id in worker.project_ids
                    for worker in self.worker_bindings):
                raise ValueError("prepared recipe requires a named worker with an explicit source preparation read grant")
            if not recipe.available:
                continue
            template = catalog.for_task_family(recipe.task_family)
            if template.scope != "specialized" or template.adapter_id != "liushanmen-visionlab-v1":
                raise ValueError("no executable named runner is registered for this task family")
            if recipe.factory_id not in self.specialized_factory_allowlist.get(template.template_id, ()):
                raise ValueError("available recipe requires an explicit specialized factory binding")
            if (recipe.template_id != template.template_id or recipe.template_version != template.version
                    or recipe.sop_version != SOP_VERSION or not recipe.config_sha256
                    or not recipe.stage_input_hashes
                    or not set(recipe.stage_input_hashes) <= {"collect", "dataset", "train", "evaluate"}):
                raise ValueError("available recipe must bind the template, SOP, configuration and stage inputs")
            if (not set(recipe.stage_source_asset_hashes) <= {"train", "evaluate"}
                    or any(stage in recipe.stage_input_hashes and stage not in recipe.stage_source_asset_hashes
                           for stage in ("train", "evaluate"))):
                raise ValueError("training and evaluation recipes must bind their predecessor artifact hash separately")
            if not any(recipe.factory_id in worker.factory_ids for worker in self.worker_bindings):
                raise ValueError("available recipe has no named worker bound to this factory")
        return self


@dataclass(frozen=True, repr=False)
class ServerRegistration:
    recipes: tuple[RecipeAvailability, ...] = ()
    worker_bindings: tuple[WorkerBinding, ...] = ()
    specialized_factory_allowlist: Mapping[str, tuple[str, ...]] | None = None


def load_server_registration(
    configured: str | None = None,
    *,
    environment: Mapping[str, str] | None = None,
) -> ServerRegistration:
    """Read one operator-owned registry; an unset path enables no factory.

    Secrets are looked up by environment-variable name and deliberately absent
    from the registry schema. Invalid configured registrations fail startup.
    """
    env = os.environ if environment is None else environment
    configured = env.get("VISION_TRAINING_REGISTRY_FILE") if configured is None else configured
    if not configured:
        return ServerRegistration()
    path = Path(configured)
    if not path.is_absolute():
        raise ValueError("VISION_TRAINING_REGISTRY_FILE must be an absolute external path")
    path = path.resolve()
    source_root = Path(__file__).resolve().parents[3]
    if path == source_root or source_root in path.parents:
        raise ValueError("vision training registry must be outside the release-managed source tree")
    config = ServerRegistrationConfig.model_validate_json(path.read_text(encoding="utf-8-sig"))
    internal_secret = env.get("INTERNAL_API_SECRET", "")
    bindings: list[WorkerBinding] = []
    used_tokens: set[str] = set()
    for worker in config.worker_bindings:
        token = env.get(worker.token_env, "")
        if len(token.encode("utf-8")) < 32 or token != token.strip():
            raise ValueError(f"worker {worker.worker_id} requires a dedicated secret of at least 32 bytes")
        if (internal_secret and hmac.compare_digest(token.encode(), internal_secret.encode())) or token in used_tokens:
            raise ValueError("each worker requires its own credential, separate from internal authentication")
        used_tokens.add(token)
        bindings.append(WorkerBinding(worker.worker_id, token, frozenset(worker.factory_ids), frozenset(worker.project_ids)))
    return ServerRegistration(config.recipes, tuple(bindings), config.specialized_factory_allowlist)
