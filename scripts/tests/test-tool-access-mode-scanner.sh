#!/usr/bin/env bash
# GUARDS=scripts/finetune/tool_access_modes.py
# INTENT-DISTILL-20261111 F-pre: the distillation data's read-only/write label comes from
# scripts/finetune/tool_access_modes.py reading each Tool's getAccessMode() from Java source.
# Steve 2026-11-11: a local model may answer read-only intents directly, so a wrong READ here is
# a write going out without confirmation. This test pins:
#   A. every factory Tool in the real tree resolves to READ or WRITE (no UNRESOLVED/UNNAMED),
#      and the scan sees the whole tree (positive control: >= 400 tools);
#   B. the two shapes that used to read as UNRESOLVED (comment inside the method; inherited
#      declaration) resolve to the right mode;
#   C. mutations go red: deleting a declaration -> rc=1 (UNDECLARED), a computed return ->
#      DYNAMIC, a parent that is not in the tree -> UNDECLARED. Never silently READ.
# Java side of the same contract: ToolAccessModeDeclarationTest (PR-selected via
# scripts/ci/java-gate-fast-patterns.txt).
set -uo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PY=""
for c in python3 python; do
    abs=$(command -v "$c" 2>/dev/null) || continue
    "$abs" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1 || continue
    PY="$abs"; break
done
[ -n "$PY" ] || { echo "FAIL: no working python interpreter"; exit 2; }

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# A. real tree
"$PY" "$ROOT/scripts/finetune/tool_access_modes.py" > "$OUT/real.log" 2>&1; rc=$?
cat "$OUT/real.log"
[ "$rc" = 0 ] || { echo "FAIL A: real tool tree has unresolved access modes (rc=$rc)"; exit 1; }
grep -q '^TOOL_ACCESS_SCAN tools=' "$OUT/real.log" || { echo "FAIL A: scan summary line missing"; exit 1; }

# B + C on a synthetic tree
"$PY" - "$ROOT/scripts/finetune" "$OUT/tree" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import tool_access_modes as t

root = Path(sys.argv[2]); impl = root / 'impl'
impl.mkdir(parents=True)
def tool(cls, name, body='', parent='AbstractBusinessTool'):
    (impl / (cls + '.java')).write_text(
        'package x;\npublic class %s extends %s {\n'
        '    @Override public String getToolName() { return "%s"; }\n%s}\n' % (cls, parent, name, body),
        encoding='utf-8')
(root / 'AbstractBusinessTool.java').write_text('package x;\npublic abstract class AbstractBusinessTool {}\n', encoding='utf-8')
READ = '    @Override\n    public AccessMode getAccessMode() {\n        // only reads\n        return AccessMode.READ;\n    }\n'
tool('CommentedRead', 'commented_read', READ)
tool('ParentRead', 'parent_read', '    @Override public AccessMode getAccessMode() { return AccessMode.READ; }\n')
tool('ChildRead', 'child_read', '', parent='ParentRead')
tool('PlainWrite', 'plain_write', '    public AccessMode getAccessMode() { return ToolExecutor.AccessMode.WRITE; }\n')
tool('Undeclared', 'undeclared_probe', '    // return AccessMode.READ;  (a comment must not count)\n')
tool('Computed', 'computed_probe', '    public AccessMode getAccessMode() { return flag ? AccessMode.READ : AccessMode.WRITE; }\n')
tool('Orphan', 'orphan_probe', '', parent='SomethingOutsideTheTree')
(impl / 'Const.java').write_text('package x;\npublic class Const extends AbstractBusinessTool {\n'
    '    private static final String TOOL_NAME = "const_read";\n'
    '    @Override public String getToolName() { return TOOL_NAME; }\n' + READ + '}\n', encoding='utf-8')

got = {k: v['mode'] for k, v in t.scan(root, impl).items()}
want = dict(commented_read='READ', parent_read='READ', child_read='READ', plain_write='WRITE',
            const_read='READ', undeclared_probe='UNDECLARED', computed_probe='DYNAMIC', orphan_probe='UNDECLARED')
bad = {k: (got.get(k), v) for k, v in want.items() if got.get(k) != v}
if bad:
    print('FAIL B/C: got != want', bad); sys.exit(1)
if set(got) != set(want):
    print('FAIL B/C: unexpected tools', sorted(set(got) ^ set(want))); sys.exit(1)
print('OK B/C', got)
PY
rc=$?
[ "$rc" = 0 ] || exit 1

# C (CLI path): a real-tree mutation turns main() red, not READ
MUT="$OUT/mut"; mkdir -p "$MUT"
cp -r "$ROOT/backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool" "$MUT/tool"
f="$MUT/tool/impl/material/GateDiagnoseBatchTool.java"
[ -f "$f" ] || { echo "FAIL C: mutation target missing"; exit 2; }
"$PY" - "$f" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8', newline='').read()
n = re.sub(r'@Override\s*public AccessMode getAccessMode\(\)\s*\{[^}]*\}', '', s)
assert n != s, 'mutation did not apply'
open(p, 'w', encoding='utf-8', newline='').write(n)
PY
"$PY" - "$ROOT/scripts/finetune" "$MUT/tool" > "$OUT/mut.log" 2>&1 <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import tool_access_modes as t
r = t.scan(Path(sys.argv[2]), Path(sys.argv[2]) / 'impl')
print('gate_diagnose_batch', r['gate_diagnose_batch']['mode'])
sys.exit(0 if r['gate_diagnose_batch']['mode'] == 'UNDECLARED' else 1)
PY
rc=$?
cat "$OUT/mut.log"
[ "$rc" = 0 ] || { echo "FAIL C: deleting the declaration did not turn the tool UNDECLARED"; exit 1; }
echo "PASS test-tool-access-mode-scanner"
