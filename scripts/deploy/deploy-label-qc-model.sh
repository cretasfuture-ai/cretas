#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
SERVER="root@47.100.235.168"
REMOTE_ROOT="/www/wwwroot/cretas/models/label-qc-bundles"
REMOTE_ENV="/www/wwwroot/cretas/.env.prod"
EXPECTED_PIPELINE="liushanmen-tray-label-shallow-v8-fallback-20260822"
BUNDLE_DIR=""
CONFIRM=""
CHECK_ONLY="0"

if [ -f "$PROJECT_ROOT/scripts/lib/deploy-common.sh" ]; then
    # shellcheck source=scripts/lib/deploy-common.sh
    source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"
else
    echo "ERROR: missing deploy-common.sh" >&2
    exit 1
fi

usage() {
    cat <<'EOF'
Usage:
  scripts/deploy/deploy-label-qc-model.sh \
    --bundle <validated-bundle-dir> \
    --confirm-prod YES-LABEL-QC-MODEL

  scripts/deploy/deploy-label-qc-model.sh \
    --bundle <validated-bundle-dir> --check-only

Uploads one immutable, SHA-bound Liushanmen label-QC model bundle, atomically
activates the v8 pipeline in .env.prod, restarts cretas-python, verifies the
authenticated runtime contract, and restores the previous environment on any
activation failure. Run only after the matching Python code and requirements
have been deployed from the exact release commit.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --bundle) BUNDLE_DIR=${2:-}; shift 2 ;;
        --confirm-prod) CONFIRM=${2:-}; shift 2 ;;
        --check-only) CHECK_ONLY="1"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ -n "$BUNDLE_DIR" ] || { echo "ERROR: --bundle is required" >&2; exit 2; }
[ -d "$BUNDLE_DIR" ] || { echo "ERROR: bundle directory not found: $BUNDLE_DIR" >&2; exit 2; }
BUNDLE_DIR=$(cd "$BUNDLE_DIR" && pwd -P)

mapfile -t BUNDLE_META < <(python - "$BUNDLE_DIR" "$EXPECTED_PIPELINE" <<'PY'
import hashlib
import json
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1]).resolve()
expected_pipeline = sys.argv[2]
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("version") != 1:
    raise SystemExit("unsupported manifest version")
if manifest.get("pipelineId") != expected_pipeline:
    raise SystemExit("pipelineId mismatch")
model_id = str(manifest.get("modelId", ""))
if not model_id or not re.fullmatch(r"[A-Za-z0-9._-]+", model_id):
    raise SystemExit("unsafe or missing modelId")
expected = {
    "production_tray": "tray.onnx",
    "production_label": "label.onnx",
    "tray_supplement": "tray-supplement.onnx",
    "generic_label": "generic-label.onnx",
    "proposal_classifier": "proposal-classifier.onnx",
    "label_fallback": "label-fallback.onnx",
}
models = manifest.get("models")
if not isinstance(models, dict) or set(models) != set(expected):
    raise SystemExit("model binding set mismatch")
for key, filename in expected.items():
    binding = models[key]
    if binding.get("file") != filename:
        raise SystemExit(f"filename mismatch for {key}")
    path = (root / filename).resolve()
    if path.parent != root or not path.is_file():
        raise SystemExit(f"missing or unsafe model file: {filename}")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != binding.get("sha256"):
        raise SystemExit(f"sha256 mismatch for {filename}")
manifest_sha = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
print(manifest_sha)
print(model_id)
PY
)

MANIFEST_SHA=${BUNDLE_META[0]:-}
MODEL_ID=${BUNDLE_META[1]:-}
# Windows Git Bash can preserve the CR emitted by the native Python runtime.
MANIFEST_SHA=${MANIFEST_SHA//$'\r'/}
MODEL_ID=${MODEL_ID//$'\r'/}
[[ "$MANIFEST_SHA" =~ ^[0-9a-f]{64}$ ]] || { echo "ERROR: invalid manifest SHA" >&2; exit 1; }
[ -n "$MODEL_ID" ] || { echo "ERROR: missing model ID" >&2; exit 1; }

log "INFO" "Validated bundle: $MODEL_ID ($MANIFEST_SHA)"
if [ "$CHECK_ONLY" = "1" ]; then
    exit 0
fi
[ "$CONFIRM" = "YES-LABEL-QC-MODEL" ] || {
    echo "ERROR: production activation requires --confirm-prod YES-LABEL-QC-MODEL" >&2
    exit 2
}
check_git_sync "$PROJECT_ROOT" "Label-QC model exact-release pre-check" "1"
if [ -n "$(git -C "$PROJECT_ROOT" status --porcelain)" ]; then
    echo "ERROR: Label-QC production activation requires a completely clean worktree" >&2
    exit 1
fi

REMOTE_TARGET="$REMOTE_ROOT/$MANIFEST_SHA"
REMOTE_UPLOAD="$REMOTE_ROOT/.upload-$MANIFEST_SHA-$$"
case "$REMOTE_TARGET" in "$REMOTE_ROOT"/[0-9a-f][0-9a-f]*) ;; *) echo "ERROR: unsafe remote target" >&2; exit 1 ;; esac
case "$REMOTE_UPLOAD" in "$REMOTE_ROOT"/.upload-[0-9a-f]*) ;; *) echo "ERROR: unsafe remote upload target" >&2; exit 1 ;; esac

ssh "$SERVER" bash -s -- "$REMOTE_ROOT" "$REMOTE_UPLOAD" <<'REMOTE_PREPARE'
set -euo pipefail
root=$1
upload=$2
[ "$root" = "/www/wwwroot/cretas/models/label-qc-bundles" ]
case "$upload" in "$root"/.upload-[0-9a-f]*) ;; *) exit 1 ;; esac
mkdir -p "$root"
if [ -e "$upload" ] || [ -L "$upload" ]; then
    rm -rf -- "$upload"
fi
mkdir "$upload"
REMOTE_PREPARE

rsync -az --delete --timeout=180 "$BUNDLE_DIR/" "$SERVER:$REMOTE_UPLOAD/"

ssh "$SERVER" bash -s -- \
    "$REMOTE_ROOT" "$REMOTE_UPLOAD" "$REMOTE_TARGET" "$MANIFEST_SHA" "$EXPECTED_PIPELINE" <<'REMOTE_INSTALL'
set -euo pipefail
root=$1
upload=$2
target=$3
expected_sha=$4
expected_pipeline=$5
[ "$root" = "/www/wwwroot/cretas/models/label-qc-bundles" ]
case "$upload" in "$root"/.upload-*) ;; *) exit 1 ;; esac
case "$target" in "$root"/[0-9a-f]*) ;; *) exit 1 ;; esac

verify_bundle() {
    python3 - "$1" "$expected_sha" "$expected_pipeline" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve()
expected_sha, expected_pipeline = sys.argv[2], sys.argv[3]
manifest_path = root / "manifest.json"
if hashlib.sha256(manifest_path.read_bytes()).hexdigest() != expected_sha:
    raise SystemExit("remote manifest SHA mismatch")
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("pipelineId") != expected_pipeline:
    raise SystemExit("remote pipeline mismatch")
for binding in manifest["models"].values():
    path = (root / binding["file"]).resolve()
    if path.parent != root or hashlib.sha256(path.read_bytes()).hexdigest() != binding["sha256"]:
        raise SystemExit(f"remote model binding mismatch: {binding['file']}")
PY
}

verify_bundle "$upload"
if [ -e "$target" ]; then
    [ -d "$target" ] && [ ! -L "$target" ]
    verify_bundle "$target"
    rm -rf -- "$upload"
else
    mv -- "$upload" "$target"
fi
REMOTE_INSTALL

log "INFO" "Activating immutable model bundle on production..."
ssh "$SERVER" bash -s -- \
    "$REMOTE_TARGET" "$REMOTE_ENV" "$MODEL_ID" <<'REMOTE_ACTIVATE'
set -euo pipefail
model_dir=$1
env_file=$2
expected_model_id=$3
runtime="/www/wwwroot/cretas/code/backend/python/venv-current/bin/python"
backup_root="/www/wwwroot/cretas/.label-qc-env-backups"

case "$model_dir" in /www/wwwroot/cretas/models/label-qc-bundles/[0-9a-f]*) ;; *) exit 1 ;; esac
[ -d "$model_dir" ] && [ ! -L "$model_dir" ]
[ -r "$env_file" ] && [ ! -L "$env_file" ]
[ -x "$runtime" ]
"$runtime" -c 'import cv2,onnxruntime; assert cv2.__version__ == "4.10.0", cv2.__version__'

mkdir -p "$backup_root"
chmod 700 "$backup_root"
backup="$backup_root/env.prod.$(date +%Y%m%d-%H%M%S).$$"
cp -p -- "$env_file" "$backup"
tmp=$(mktemp /www/wwwroot/cretas/.env.prod.label-qc.XXXXXX)

rollback() {
    status=$?
    if [ "$status" -ne 0 ]; then
        cp -p -- "$backup" "$env_file"
        systemctl restart cretas-python || true
        echo "ERROR: Label-QC activation failed; previous environment restored" >&2
    fi
    rm -f -- "$tmp"
    exit "$status"
}
trap rollback EXIT

awk '!/^[[:space:]]*(export[[:space:]]+)?LABEL_QC_(SCREENING|PIPELINE|MODEL_DIR|VL_REVIEW)=/' \
    "$env_file" > "$tmp"
cat >> "$tmp" <<EOF
LABEL_QC_SCREENING=1
LABEL_QC_PIPELINE=v8
LABEL_QC_MODEL_DIR=$model_dir
LABEL_QC_VL_REVIEW=0
EOF
chmod --reference="$env_file" "$tmp"
chown --reference="$env_file" "$tmp"
mv -f -- "$tmp" "$env_file"

systemctl restart cretas-python
systemctl is-active --quiet cretas-python
# systemd reports active before uvicorn has bound its socket; keep rollback
# armed while giving the real HTTP health endpoint bounded time to come up.
health=$(curl -fsS --retry 15 --retry-connrefused --retry-delay 1 --max-time 5 http://127.0.0.1:8083/health)
printf '%s' "$health" | "$runtime" -c 'import json,sys; d=json.load(sys.stdin); assert d.get("status") == "healthy"; assert d.get("postgres") == "connected"'

set -a
. "$env_file"
set +a
[ -n "${INTERNAL_API_SECRET:-}" ]
status_body=$(curl -fsS --max-time 90 \
    -H "X-Internal-Secret: $INTERNAL_API_SECRET" \
    -H "X-Factory-Id: LIUSHANMEN" \
    http://127.0.0.1:8083/api/label-qc/screening-status)
printf '%s' "$status_body" | "$runtime" -c \
    'import json,sys; d=json.load(sys.stdin)["data"]; expected=sys.argv[1]; assert d["screeningActive"] is True; assert d["modelsAvailable"] is True; assert d["pipeline"] == "v8"; assert d["failClosed"] is True; assert d["customerAutoPassSupported"] is True; assert d["modelId"] == expected' \
    "$expected_model_id"

trap - EXIT
rm -f -- "$tmp"
echo "LABEL_QC_MODEL_ID=$expected_model_id"
echo "LABEL_QC_MODEL_DIR=$model_dir"
echo "LABEL_QC_ENV_BACKUP=$backup"
REMOTE_ACTIVATE

log "INFO" "Liushanmen v8 model activated and authenticated runtime checks passed"
