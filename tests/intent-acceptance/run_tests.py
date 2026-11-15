"""无 pytest 环境 (ops-script-tests 的 CI Python) 下的最小测试跑器: 跑 test_acceptance_tools.py 里所有 test_* 函数, 支持 tmp_path。
退出码: 0 全过且至少执行 1 个 / 1 有失败 / 2 一个都没执行。pytest 可用时直接 python -m pytest tests/intent-acceptance。
"""
import importlib
import inspect
import sys
import tempfile
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
mod = importlib.import_module("test_acceptance_tools")
ran = failed = 0
for name, fn in sorted(vars(mod).items()):
    if name.startswith("test_") and callable(fn):
        ran += 1
        try:
            if "tmp_path" in inspect.signature(fn).parameters:
                with tempfile.TemporaryDirectory() as td:
                    fn(Path(td))
            else:
                fn()
        except Exception:  # noqa: BLE001
            failed += 1
            print("FAILED", name)
            traceback.print_exc()
print("%d passed, %d failed" % (ran - failed, failed))
sys.exit(2 if ran == 0 else (1 if failed else 0))
