"""Python Gate 的「真 PG 清理语义」步骤入口（不是测试文件，pytest 不会收集它）。

跑 test_factory_etl_prune_pg.py（带 integration 标记，主 pytest 步骤 `-k "not integration"` 会跳过它），
并断言：恰好执行 EXPECTED 条、0 失败、0 跳过。没设库 / 全被跳过 / 少跑了，都是红 —— 不能让这组 SQL 语义测试
「看起来绿其实没跑」。新增/删除该文件里的测试时同步改 EXPECTED。

用法（在 backend/python 下，由 scripts/ci/run-with-test-postgres.sh 提供 SMARTBI_PG_DSN）:
    bash scripts/ci/run-with-test-postgres.sh python smartbi/tests/ci_prune_pg_gate.py
"""
import os
import sys

import pytest

EXPECTED = 14


class _Count:
    def __init__(self):
        self.passed = self.failed = self.skipped = 0

    def pytest_runtest_logreport(self, report):
        if report.when == "call" and report.passed:
            self.passed += 1
        elif report.failed:
            self.failed += 1
        elif report.skipped:
            self.skipped += 1


def main() -> int:
    dsn = os.environ.get("SMARTBI_PG_DSN")
    if not dsn:
        print("PRUNE_PG_GATE FAIL: SMARTBI_PG_DSN 未设置（没有测试库就不能算过）")
        return 2
    os.environ["PG_TEST_DSN"] = dsn
    counter = _Count()
    rc = pytest.main(
        ["smartbi/tests/test_factory_etl_prune_pg.py", "-m", "integration", "-v", "-p", "no:cacheprovider", "--tb=short"],
        plugins=[counter],
    )
    print(
        f"PRUNE_PG_GATE test_factory_etl_prune_pg passed={counter.passed} failed={counter.failed} "
        f"skipped={counter.skipped} expected={EXPECTED}"
    )
    ok = rc == 0 and counter.passed == EXPECTED and counter.failed == 0 and counter.skipped == 0
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
