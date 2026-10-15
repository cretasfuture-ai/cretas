"""Operator registration is explicit, factory-scoped, and secret-free on disk."""
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from vision_training.server_registration import load_server_registration


def write_registry(tmp_path, value):
    path = tmp_path / "operator-registry.json"
    path.write_text(json.dumps(value), encoding="utf-8")
    return str(path)


def config():
    return {
        "recipes": [{"recipe_id": "recipe-A", "factory_id": "FACTORY-A",
                     "task_family": "liushanmen-tray-label-detection", "recipe_version": "v1",
                     "available": True, "template_id": "liushanmen-tray", "template_version": "v1",
                     "sop_version": "vision-training-sop-v1", "config_sha256": "a" * 64,
                     "data_version": "data-v1", "human_standard_version": "human-v1",
                     "independent_acceptance_version": "accept-v1",
                     "stage_input_hashes": {"collect": "b" * 64}}],
        "worker_bindings": [{"worker_id": "local-A", "token_env": "TEST_WORKER_TOKEN",
                             "factory_ids": ["FACTORY-A"], "project_ids": ["project-A"]}],
        "specialized_factory_allowlist": {"liushanmen-tray": ["FACTORY-A"]},
    }


def test_unconfigured_registry_does_not_enable_any_factory_or_worker(tmp_path):
    registration = load_server_registration(environment={})
    assert registration.recipes == () and registration.worker_bindings == ()
    assert not registration.specialized_factory_allowlist
    assert not list(tmp_path.iterdir())


def test_external_registry_resolves_dedicated_worker_secret_without_persisting_it(tmp_path):
    path = write_registry(tmp_path, config())
    token = "worker-test-dedicated-credential-32-bytes"
    result = load_server_registration(path, environment={"TEST_WORKER_TOKEN": token})
    assert result.recipes[0].factory_id == "FACTORY-A"
    assert result.worker_bindings[0].factory_ids == frozenset({"FACTORY-A"})
    assert result.worker_bindings[0].project_ids == frozenset({"project-A"})
    assert result.worker_bindings[0].token == token
    assert token not in Path(path).read_text() and token not in repr(result)


@pytest.mark.parametrize("change", ["unbound_factory", "duplicate_worker", "duplicate_recipe", "generic_runner", "missing_hash", "unexpected_secret", "missing_human_version", "unbound_train_source"])
def test_invalid_or_generalized_registration_is_rejected(tmp_path, change):
    value = config()
    if change == "unbound_factory":
        value["specialized_factory_allowlist"] = {}
    elif change == "duplicate_worker":
        value["worker_bindings"] *= 2
    elif change == "duplicate_recipe":
        value["recipes"] *= 2
    elif change == "generic_runner":
        value["recipes"][0].update(task_family="packaging-detection", template_id="generic-packaging-detection")
    elif change == "missing_hash":
        value["recipes"][0]["config_sha256"] = None
    elif change == "missing_human_version":
        value["recipes"][0]["human_standard_version"] = None
    elif change == "unbound_train_source":
        value["recipes"][0]["stage_input_hashes"] = {"train": "b" * 64}
    else:
        value["worker_bindings"][0]["token"] = "never-accept-inline-secrets"
    with pytest.raises(ValueError):
        load_server_registration(write_registry(tmp_path, value), environment={"TEST_WORKER_TOKEN": "x" * 40})


def test_training_input_file_and_source_artifact_are_separately_bound(tmp_path):
    value = config()
    value["recipes"][0]["stage_input_hashes"] = {"train": "b" * 64}
    value["recipes"][0]["stage_source_asset_hashes"] = {"train": "c" * 64}
    registration = load_server_registration(write_registry(tmp_path, value), environment={"TEST_WORKER_TOKEN": "x" * 40})
    assert registration.recipes[0].stage_input_hashes["train"] == "b" * 64
    assert registration.recipes[0].stage_source_asset_hashes["train"] == "c" * 64


@pytest.mark.parametrize("env", [{}, {"TEST_WORKER_TOKEN": "short"},
    {"TEST_WORKER_TOKEN": "x" * 40, "INTERNAL_API_SECRET": "x" * 40}])
def test_missing_short_or_shared_internal_credential_fails_closed(tmp_path, env):
    with pytest.raises(ValueError):
        load_server_registration(write_registry(tmp_path, config()), environment=env)


def test_registry_cannot_live_in_release_tree_or_relative_path():
    with pytest.raises(ValueError, match="absolute"):
        load_server_registration("relative.json", environment={})
    source_root = Path(__file__).resolve().parents[3]
    with pytest.raises(ValueError, match="source tree"):
        load_server_registration(str(source_root / "operator-registry.json"), environment={})
