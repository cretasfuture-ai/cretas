"""Generate and verify the immutable factory-only legacy evaluator projection.

Historical source identity and implementation identity are deliberately distinct.
The generated runtime contains only data validation and offline factory evaluation;
Git is used here during development/CI, never by the runtime evaluator.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_COMMIT = "cc7a68879116df17c7306e55ad5aab441af54ac2"
SOURCE_BUILD = "31999b828125844a2fcbbc75edef466bd24756bd2c85d35258c75d5bebe3ac47"
MODULE = ROOT / "backend/python/smartbi/agent/eval/legacy/factory_31999.py"
MANIFEST = MODULE.with_name("provenance.json")
SOURCES = {
    "runner.py": ("backend/python/smartbi/agent/eval/runner.py", "bb3ed8f72f594eaba45d3b1f218a2b164c9df650"),
    "runtime/evaluation.py": ("backend/python/smartbi/agent/runtime/evaluation.py", "8c320485740acc2983a804f5b00f04169c7c95ad"),
    "validation.py": ("backend/python/smartbi/agent/eval/validation.py", "72ac1abb14472911f347eef137a9b273c36f1faf"),
    "contracts.py": ("backend/python/smartbi/agent/eval/contracts.py", "921078cae274bb8c6dde83d75a5562beb04f9b9a"),
    "runtime/run_contracts.py": ("backend/python/smartbi/agent/runtime/run_contracts.py", "ab86e26177a1606eea945d48fe6c9b00df643ccf"),
}
VALIDATION_NODES = (
    "MAX_CASES", "MAX_SNAPSHOT_BYTES", "MAX_AGENT_OPS_PAYLOAD_BYTES", "MAX_TOOLS",
    "MAX_NUMERIC_REFS", "_ID", "AgentOpsValidationError", "validate_actual_snapshot",
    "validate_actual_snapshots", "validate_runner_bounds_snapshot", "_tools",
    "_numeric_refs", "_identifier", "_bounded_int", "_bounded_json",
)
EVALUATION_NODES = (
    "OfflineEvaluation", "OfflineCaseExpectation", "OfflineCaseSnapshot",
    "evaluate_offline_case", "_numeric_refs_match", "_is_subsequence",
)
RUNNER_NODES = (
    "FACTORY_EVALUATOR_VERSION", "RunnerBounds", "OfflineBatchRunner",
    "FactoryOfflineBatchRunner", "aggregate_case_results",
)
PRELUDE = '''"""Generated frozen factory compatibility execution; regenerate with the provenance gate.

This implementation has its own digest. It does not claim to be the historical
two-file source artifact and provides no restaurant runtime or external tools.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Mapping, Sequence

# EvalSetRecord is only an annotation in the frozen runner, never a constructor.
EvalSetRecord = Any
# Preserve the old validator's historical mismatch sentinel as data, not a route.
RouteCode = SimpleNamespace(GROSS_MARGIN_DECLINE_ATTRIBUTION=SimpleNamespace(value={sentinel!r}))
# Preserve original method expressions without importing the retired runtime.
runtime_evaluation = sys.modules[__name__]
HISTORICAL_SOURCE_BUILD = {source_build!r}
'''
VERIFIER = '''
def verify_local_artifact(expected_manifest_digest: str, *, artifact_dir=None) -> bool:
    """Validate local immutable bytes only; never consult Git or a network."""
    if not isinstance(expected_manifest_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_manifest_digest):
        return False
    directory = Path(artifact_dir) if artifact_dir is not None else Path(__file__).parent
    try:
        raw = (directory / "provenance.json").read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected_manifest_digest:
            return False
        manifest = json.loads(raw)
        if manifest["historical"]["evaluatorBuild"] != HISTORICAL_SOURCE_BUILD:
            return False
        module = (directory / "factory_31999.py").read_bytes()
        return hashlib.sha256(module).hexdigest() == manifest["implementation"]["moduleSha256"]
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        return False
'''


def sha(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def compute_build(artifacts: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    for name, payload in sorted(artifacts.items()):
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest()


def load_sources(repo: Path = ROOT, commit: str = DEFAULT_COMMIT) -> dict[str, bytes]:
    if commit != DEFAULT_COMMIT:
        raise AssertionError("historical source commit is immutable")
    result = {}
    for name, (path, expected_blob) in SOURCES.items():
        actual = subprocess.check_output(["git", "-C", str(repo), "rev-parse", f"{commit}:{path}"], text=True).strip()
        if actual != expected_blob:
            raise AssertionError(f"source blob changed: {name}")
        result[name] = subprocess.check_output(["git", "-C", str(repo), "cat-file", "blob", expected_blob])
    if compute_build({name: result[name] for name in ("runner.py", "runtime/evaluation.py")}) != SOURCE_BUILD:
        raise AssertionError("historical two-file source build cannot be reproduced")
    return result


def named_nodes(payload: bytes) -> dict[str, ast.AST]:
    result = {}
    for node in ast.parse(payload.decode("utf-8")).body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            result[node.name] = node
        elif isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name):
                    result[target.id] = node
    return result


def node_text(payload: bytes, node: ast.AST, *, omit_identity: bool = False) -> str:
    """Copy exact original node text, including decorators; no expression rewrites."""
    lines = payload.decode("utf-8").splitlines(keepends=True)
    start = min([node.lineno] + [item.lineno for item in getattr(node, "decorator_list", [])])
    omitted = set()
    if omit_identity:
        if not isinstance(node, ast.ClassDef) or node.name != "OfflineBatchRunner":
            raise AssertionError("identity omission is restricted to the old generic runner")
        for item in node.body:
            if isinstance(item, ast.Assign) and any(
                isinstance(target, ast.Name) and target.id in {"evaluator_version", "evaluator_build"}
                for target in item.targets
            ):
                omitted.update(range(item.lineno, item.end_lineno + 1))
        if len(omitted) != 2:
            raise AssertionError("expected precisely two generic runner identity declarations")
    return "".join(lines[index - 1] for index in range(start, node.end_lineno + 1) if index not in omitted).rstrip() + "\n"


def expected_projection(blobs: dict[str, bytes]) -> tuple[bytes, bytes]:
    route_node = named_nodes(blobs["runtime/run_contracts.py"])["RouteCode"]
    sentinel_values = [item.value.value for item in route_node.body if isinstance(item, ast.Assign)
                       and any(isinstance(target, ast.Name) and target.id == "GROSS_MARGIN_DECLINE_ATTRIBUTION" for target in item.targets)
                       and isinstance(item.value, ast.Constant) and isinstance(item.value.value, str)]
    if sentinel_values != ["GROSS_MARGIN_DECLINE_ATTRIBUTION"]:
        raise AssertionError("historical validator sentinel changed")
    sections = [PRELUDE.format(sentinel=sentinel_values[0], source_build=SOURCE_BUILD)]
    projection = []
    for source_name, names in (("validation.py", VALIDATION_NODES),
                               ("runtime/evaluation.py", EVALUATION_NODES),
                               ("runner.py", RUNNER_NODES)):
        nodes = named_nodes(blobs[source_name])
        for name in names:
            node = nodes[name]
            original = node_text(blobs[source_name], node)
            rendered = node_text(blobs[source_name], node, omit_identity=name == "OfflineBatchRunner")
            sections.append(f"# Frozen source: {source_name}::{name}\n" + rendered)
            projection.append({"source": source_name, "node": name, "sourceTextSha256": sha(original.encode()),
                               "sourceAstSha256": sha(ast.dump(node, include_attributes=False).encode()),
                               "projectedTextSha256": sha(rendered.encode())})
    sections.append(VERIFIER)
    module = ("\n\n".join(sections).rstrip() + "\n").encode("utf-8")
    manifest = {
        "format": "factory-legacy-projection-v1",
        "historical": {"commit": DEFAULT_COMMIT, "evaluatorVersion": "factory-offline-v1", "evaluatorBuild": SOURCE_BUILD,
                       "sources": {name: {"path": SOURCES[name][0], "blob": SOURCES[name][1], "sha256": sha(payload)}
                                   for name, payload in sorted(blobs.items())}},
        "implementation": {"module": "factory_31999.py", "moduleSha256": sha(module),
                           "identityKind": "verified-compatibility-implementation"},
        "generation": {"version": 1, "projection": projection,
                       "mechanicalBindings": ["EvalSetRecord annotation -> Any", "runtime_evaluation -> current factory-only module",
                                              "RouteCode historical validation sentinel -> immutable scalar namespace"],
                       "omittedIdentityDeclarations": ["OfflineBatchRunner.evaluator_version", "OfflineBatchRunner.evaluator_build"],
                       "algorithmExpressionChanges": []},
    }
    return module, (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def check(repo: Path = ROOT, *, module_path: Path = MODULE, manifest_path: Path = MANIFEST) -> dict:
    expected_module, expected_manifest = expected_projection(load_sources(repo))
    if module_path.read_bytes() != expected_module:
        raise AssertionError("factory projection differs from exact trusted-source generation")
    if manifest_path.read_bytes() != expected_manifest:
        raise AssertionError("provenance differs from the trusted-source projection manifest")
    return {"ok": True, "historicalSourceBuild": SOURCE_BUILD,
            "implementationSha256": sha(expected_module), "manifestSha256": sha(expected_manifest)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--write", action="store_true", help="Regenerate only the checked-in factory projection and provenance")
    args = parser.parse_args()
    try:
        if args.write:
            module, manifest = expected_projection(load_sources(args.repo))
            MODULE.parent.mkdir(parents=True, exist_ok=True)
            MODULE.write_bytes(module)
            MANIFEST.write_bytes(manifest)
        print(json.dumps(check(args.repo), sort_keys=True))
        return 0
    except (AssertionError, KeyError, ValueError) as exc:
        print(f"FACTORY_PROVENANCE_MISMATCH: {exc}", file=sys.stderr)
        return 1
    except (OSError, subprocess.CalledProcessError) as exc:
        print(f"FACTORY_PROVENANCE_UNMEASURED: {type(exc).__name__}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
