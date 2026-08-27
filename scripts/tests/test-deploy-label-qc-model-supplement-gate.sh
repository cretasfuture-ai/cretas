#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-label-qc-model.sh"
WORK_DIR=$(mktemp -d)
trap 'rm -rf -- "$WORK_DIR"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

[ "$(grep -Fxc 'optional = {"label_supplement": "label-supplement.onnx"}' "$DEPLOY_SCRIPT")" -eq 2 ] \
    || fail "local and remote validators must both bind the optional supplement filename"
[ "$(grep -Fc 'orphan label supplement configuration' "$DEPLOY_SCRIPT")" -eq 2 ] \
    || fail "local and remote validators must both reject orphan supplement configuration"

write_bundle() {
    local target=$1
    local mode=$2
    python - "$target" "$mode" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
root.mkdir(parents=True)
required = {
    "production_tray": "tray.onnx",
    "production_label": "label.onnx",
    "tray_supplement": "tray-supplement.onnx",
    "generic_label": "generic-label.onnx",
    "proposal_classifier": "proposal-classifier.onnx",
    "label_fallback": "label-fallback.onnx",
}
models = {}
for key, filename in required.items():
    payload = f"fixture:{key}".encode()
    (root / filename).write_bytes(payload)
    models[key] = {"file": filename, "sha256": hashlib.sha256(payload).hexdigest()}
manifest = {
    "version": 1,
    "pipelineId": "liushanmen-tray-label-shallow-v8-fallback-20260822",
    "modelId": f"test-{mode}",
    "models": models,
}
if mode != "legacy":
    payload = b"fixture:label-supplement"
    (root / "label-supplement.onnx").write_bytes(payload)
    models["label_supplement"] = {
        "file": "label-supplement.onnx",
        "sha256": hashlib.sha256(payload).hexdigest(),
    }
    manifest["labelSupplementClasses"] = [1]
    manifest["labelSupplementConfidence"] = 0.30
if mode == "extra-binding":
    models["unexpected"] = models["production_label"].copy()
if mode == "wrong-filename":
    models["label_supplement"]["file"] = "label.onnx"
if mode == "missing-classes":
    manifest.pop("labelSupplementClasses")
if mode == "out-of-range-class":
    manifest["labelSupplementClasses"] = [2]
if mode == "duplicate-class":
    manifest["labelSupplementClasses"] = [1, 1]
if mode == "low-confidence":
    manifest["labelSupplementConfidence"] = 0.19
if mode == "bad-hash":
    models["label_supplement"]["sha256"] = "0" * 64
if mode == "orphan-config":
    models.pop("label_supplement")
(root / "manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
PY
}

expect_pass() {
    local mode=$1
    local target="$WORK_DIR/$mode"
    write_bundle "$target" "$mode"
    "$DEPLOY_SCRIPT" --bundle "$target" --check-only >/dev/null \
        || fail "$mode bundle should pass"
}

expect_fail() {
    local mode=$1
    local target="$WORK_DIR/$mode"
    write_bundle "$target" "$mode"
    if "$DEPLOY_SCRIPT" --bundle "$target" --check-only >/dev/null 2>&1; then
        fail "$mode bundle unexpectedly passed"
    fi
}

expect_pass legacy
expect_pass supplement
expect_fail extra-binding
expect_fail wrong-filename
expect_fail missing-classes
expect_fail out-of-range-class
expect_fail duplicate-class
expect_fail low-confidence
expect_fail bad-hash
expect_fail orphan-config

printf 'PASS: legacy and constrained supplement bundles pass; malformed bundles fail closed\n'
