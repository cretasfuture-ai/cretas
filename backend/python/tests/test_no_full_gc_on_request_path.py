"""Gate: no full gc.collect() inside SmartBI code, and the startup heap is frozen.

2026-10-12 F006 upload: a worker sat inside `gc.collect()` (materializer.py, on
the /reclassify request path) for >4.6s — the full collection walked ~526k
startup objects whose pages had been swapped out, holding the GIL the whole
time. uvicorn's supervisor pings each worker with timeout_worker_healthcheck=5;
the ping thread could not get the GIL, so the supervisor killed the worker with
requests in flight ("Child process died" → Java "unexpected end of stream" →
sheet 处理失败). Measured on prod: full collect 0.18s on a fresh worker heap,
0.000s after gc.freeze().

Two properties guarded here (AST, not regex):
1. smartbi/** does not call a full collection (`gc.collect()` / `gc.collect(2)`)
   outside memory_cleanup.py, the one explicit place for it (its callers run it
   after gc.freeze(), so it only walks post-startup objects).
2. main.lifespan calls gc.freeze() before its first `yield`.
"""
from __future__ import annotations

import ast
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {ROOT / "smartbi" / "services" / "memory_cleanup.py"}


def _is_full_gc_collect(node: ast.Call) -> bool:
    func = node.func
    if not (isinstance(func, ast.Attribute) and func.attr == "collect"):
        return False
    if not (isinstance(func.value, ast.Name) and func.value.id in {"gc", "_gc"}):
        return False
    if not node.args and not node.keywords:
        return True  # gc.collect() == generation 2
    gen = node.args[0] if node.args else node.keywords[0].value
    return not (isinstance(gen, ast.Constant) and gen.value in (0, 1))


def _full_collect_sites(path: Path):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    return [n.lineno for n in ast.walk(tree) if isinstance(n, ast.Call) and _is_full_gc_collect(n)]


def test_detector_sees_the_shapes_it_must_reject():
    src = "import gc\nimport gc as _gc\ngc.collect()\n_gc.collect()\ngc.collect(2)\ngc.collect(generation=2)\ngc.collect(0)\n"
    tree = ast.parse(src)
    hits = [n.lineno for n in ast.walk(tree) if isinstance(n, ast.Call) and _is_full_gc_collect(n)]
    assert hits == [3, 4, 5, 6]


def test_no_full_gc_collect_in_smartbi_outside_memory_cleanup():
    files = [
        p for p in (ROOT / "smartbi").rglob("*.py")
        if p not in ALLOWED and "tests" not in p.parts
    ]
    assert len(files) > 200, f"scanned only {len(files)} files — wrong root?"
    offenders = {str(p.relative_to(ROOT)): lines for p in files if (lines := _full_collect_sites(p))}
    assert offenders == {}, (
        "full gc.collect() on a request/background path can hold the GIL past "
        f"uvicorn's 5s worker health check and get the worker killed: {offenders}"
    )


def test_lifespan_freezes_startup_heap_before_serving():
    tree = ast.parse((ROOT / "main.py").read_text(encoding="utf-8"))
    lifespan = next(
        n for n in ast.walk(tree)
        if isinstance(n, ast.AsyncFunctionDef) and n.name == "lifespan"
    )
    first_yield = min(
        n.lineno for n in ast.walk(lifespan) if isinstance(n, (ast.Yield, ast.Expr)) and (
            isinstance(n, ast.Yield) or isinstance(getattr(n, "value", None), ast.Yield))
    )
    freeze_lines = [
        n.lineno for n in ast.walk(lifespan)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute)
        and n.func.attr == "freeze" and isinstance(n.func.value, ast.Name) and n.func.value.id == "gc"
    ]
    assert freeze_lines, "main.lifespan no longer calls gc.freeze()"
    assert min(freeze_lines) < first_yield, "gc.freeze() must run before the app starts serving"
