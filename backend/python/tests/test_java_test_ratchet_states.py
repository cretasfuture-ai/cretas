"""夜间棘轮的判定：「不再失败」与「不再存在」必须分开。

`scripts/ci/java-test-ratchet.py` 是 Java 夜间跑批**唯一**的判定入口，
而它此前没有任何测试。这份补上最要紧的那部分。

## 它守什么

2026-08-27 实测：那个脚本把 `fixed = baseline - failing`，于是

    一个**被删掉**的红测试 → 不在 failing 里 → 报「✅ 已修好」

**那等于告诉人「删掉它就行了」。** 实测在基线里逮到一条幽灵：
`MaterialConsumptionNotesAndPlanRefTest.useWritesNoConsumptionRowWithoutAPlan`
—— 那个类已被改名，条目永远匹配不上。

⇒ 拆成两个集合，**措辞必须不同**，因为下一步动作相反：
  · 修好了     → 删基线条目
  · 不存在了   → 先确认是改名还是**被删掉了**
"""

from __future__ import annotations

import io
import os
import subprocess
import sys

import pytest

_HERE = os.path.dirname(os.path.abspath(__file__))
_SCRIPT = os.path.normpath(os.path.join(_HERE, "..", "..", "..", "scripts", "ci", "java-test-ratchet.py"))


def _write_report(reports_dir: str, cls: str, cases: list[tuple[str, bool]]) -> None:
    """cases: [(method, failed)]  —— 写一份最小的 surefire XML。"""
    body = []
    for method, failed in cases:
        if failed:
            body.append(
                '  <testcase classname="%s" name="%s">'
                '<failure message="boom"/></testcase>' % (cls, method)
            )
        else:
            body.append('  <testcase classname="%s" name="%s"/>' % (cls, method))
    xml = '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="%s" tests="%d">\n%s\n</testsuite>\n' % (
        cls, len(cases), "\n".join(body),
    )
    path = os.path.join(reports_dir, "TEST-%s.xml" % cls)
    io.open(path, "w", encoding="utf-8", newline="").write(xml)


def _run(reports_dir: str, baseline_path: str):
    proc = subprocess.run(
        [sys.executable, _SCRIPT, "--reports", reports_dir, "--baseline", baseline_path,
         "--min-tests", "1", "--min-ratio", "0.0"],
        capture_output=True, text=True, encoding="utf-8",
    )
    return proc.returncode, proc.stdout


@pytest.fixture()
def env(tmp_path):
    reports = tmp_path / "surefire-reports"
    reports.mkdir()
    baseline = tmp_path / "known-failing-tests.txt"
    return str(reports), str(baseline)


def test_script_is_where_we_think_it_is():
    """阳性对照：脚本找得到。找不到时下面每一条都会以错误的理由红。"""
    assert os.path.isfile(_SCRIPT), _SCRIPT


def test_still_failing_is_not_reported_as_fixed(env):
    reports, baseline = env
    _write_report(reports, "com.x.AlphaTest", [("a", True)])
    io.open(baseline, "w", encoding="utf-8", newline="").write("com.x.AlphaTest.a\n")

    rc, out = _run(reports, baseline)
    assert rc == 0, out
    assert "已修好" not in out, out
    assert "没跑到" not in out, out


def test_ran_and_passed_is_reported_as_fixed(env):
    reports, baseline = env
    _write_report(reports, "com.x.AlphaTest", [("a", False)])
    io.open(baseline, "w", encoding="utf-8", newline="").write("com.x.AlphaTest.a\n")

    rc, out = _run(reports, baseline)
    assert rc == 0, out
    assert "已修好" in out, out
    # 🔴 关键：真的修好了，就**不能**同时被算成「没跑到」
    assert "没跑到" not in out, out


def test_deleted_test_is_not_reported_as_fixed(env):
    """🔴 本文件的核心：删掉一个红的测试，⛔ 不许报「已修好」。"""
    reports, baseline = env
    # 基线里有 com.x.GhostTest.g，但这一轮**根本没有**这个类的报告
    _write_report(reports, "com.x.AlphaTest", [("a", False)])
    io.open(baseline, "w", encoding="utf-8", newline="").write(
        "com.x.AlphaTest.a\ncom.x.GhostTest.g\n"
    )

    rc, out = _run(reports, baseline)
    assert rc == 0, out
    assert "com.x.GhostTest.g" in out, out

    ghost_line = next(l for l in out.splitlines() if "com.x.GhostTest.g" in l)
    fixed_idx = out.index("已修好")
    vanished_idx = out.index("没跑到")
    ghost_idx = out.index(ghost_line)

    # 幽灵必须出现在「没跑到」那一节，而不是「已修好」那一节
    assert vanished_idx < ghost_idx, out
    assert not (fixed_idx < ghost_idx < vanished_idx), \
        "被删掉的测试被算进了「已修好」——那等于告诉人删掉它就行了\n" + out
    # 措辞必须带上那句禁令，否则读的人不知道下一步该干什么
    assert "删掉一个红的测试不是修好它" in out, out


def test_new_failure_still_turns_it_red(env):
    """阴性对照：拆分不能把「新增红」这条主判据弄坏。"""
    reports, baseline = env
    _write_report(reports, "com.x.AlphaTest", [("a", True)])
    io.open(baseline, "w", encoding="utf-8", newline="").write("")

    rc, out = _run(reports, baseline)
    assert rc == 1, out
    assert "新增" in out, out
