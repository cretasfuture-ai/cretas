# -*- coding: utf-8 -*-
"""变异对照: 三处改动**各自**在守什么。

⛔ 只证明「字符串替换成功」不算（本仓形态 C‴ 栽过）。每条变异先打印一个
   **可观测量**证明行为真的变了，再看断言红不红。行为没变 ⇒ 这轮变异不作数。

⚠️ 文本模式 + `newline=''`（硬约束 5）：⛔ 不用 bytes 字面量（中文注释会
   `SyntaxError`），也不用 `write_text`（Windows 会把整文件转 CRLF）。
"""
from __future__ import annotations

import io
import os
import subprocess
import sys

ROUTER = "smartbi/gold/restaurant/restaurant_ops_router.py"
TESTS = "tests/test_future_window_is_not_a_fake_outage.py"

#: (标签, 原串, 替换串, 这条变异该让哪些测试红)
MUTATIONS = [
    (
        "M1 撤掉第一半: 整段在未来不再走未来通道(退回只截 end)",
        "        if start > anchor:\n",
        "        if False:  # MUTATION M1\n",
    ),
    (
        "M2 拿掉 guard-decline 的 key: 正文发不到用户手里",
        '            "missing_reference": "future_window",\n',
        '            "mutated_reference": "future_window",  # MUTATION M2\n',
    ),
    (
        "M3 撤掉第二半: 派发处不再短路未来窗口",
        "        resolver_supports_explicit_window(code)\n",
        "        False  # MUTATION M3\n",
    ),
]

PROBE = """
import datetime, asyncio, sys
sys.path.insert(0, '.'); sys.path.insert(0, 'smartbi')
from smartbi.gold.restaurant import restaurant_ops_router as R
T = datetime.date(2026, 8, 19)
Q = '2026年8月25号到8月31号的营收是多少'
print('OBS_range_label=%r' % (R._resolve_sales_date_range(Q, today=T),))
print('OBS_meta_keys=%r' % sorted(R._future_window_answer('X', Q).meta))
S = R.OpsAnswer(code='SENTINEL', title='s', answer_text='s', charts=[], kpis=[], meta={})
async def _s(smartbi_pool, factory_id, *, date_range=None, **kw):
    return S
R._RESOLVERS = {'RESTAURANT_OPS_SALES_SUMMARY': _s}
got = asyncio.run(R.resolve_by_code('RESTAURANT_OPS_SALES_SUMMARY', None,
                                    'MOCK_REST', query=Q, role='restaurant_manager'))
print('OBS_dispatch_short_circuited=%r' % (got is not S,))
"""


def _read(p):
    return io.open(p, "r", encoding="utf-8", newline="").read()


def _write(p, s):
    io.open(p, "w", encoding="utf-8", newline="").write(s)


def _run(args):
    r = subprocess.run(args, capture_output=True, text=True)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def main() -> int:
    py = sys.executable
    original = _read(ROUTER)

    print("=" * 84)
    print("基线（未变异）")
    _rc, out = _run([py, "-c", PROBE])
    for line in out.splitlines():
        if line.startswith("OBS_"):
            print("  " + line)
    rc, out = _run([py, "-m", "pytest", TESTS, "-q", "--no-header"])
    print("  测试 EXIT=%d  %s" % (rc, out.strip().splitlines()[-1]))
    if rc != 0:
        print("⛔ rc=2 基线自己就是红的 —— 变异读数无意义")
        return 2

    failures = []
    try:
        for label, old, new in MUTATIONS:
            print("=" * 84)
            print(label)
            n = original.count(old)
            print("  锚点出现次数 = %d（必须恰好 1）" % n)
            if n != 1:
                print("  ⛔ rc=2 锚点不唯一 —— 这条变异没打到确定的位置")
                failures.append((label, "anchor"))
                continue
            _write(ROUTER, original.replace(old, new, 1))
            # ① 先证明**行为**变了，⛔ 不是证明字符串换掉了
            _rc, out = _run([py, "-c", PROBE])
            obs = [l for l in out.splitlines() if l.startswith("OBS_")]
            if not obs:
                print("  ⛔ rc=2 变异后探针跑不起来（语法/import 坏了）:")
                print("     " + out.strip().splitlines()[-1] if out.strip() else "")
                failures.append((label, "probe_dead"))
                _write(ROUTER, original)
                continue
            for line in obs:
                print("  " + line)
            # ② 再看断言
            rc, out = _run([py, "-m", "pytest", TESTS, "-q", "--no-header"])
            last = out.strip().splitlines()[-1] if out.strip() else ""
            print("  测试 EXIT=%d  %s" % (rc, last))
            if rc == 0:
                print("  🔴 变异不红 —— 先查是不是变异没生效（看上面 OBS_），"
                      "再谈断言有没有用")
                failures.append((label, "green"))
            else:
                names = [l.split("::")[-1].split(" ")[0]
                         for l in out.splitlines() if l.startswith("FAILED")]
                print("  ✅ 红了，红的是: %s" % ("、".join(names) or "?"))
            _write(ROUTER, original)
    finally:
        _write(ROUTER, original)

    print("=" * 84)
    same = _read(ROUTER) == original
    print("撤回后源码与原文逐字相同 = %s" % same)
    if not same:
        print("⛔ rc=2 撤回没干净")
        return 2
    if failures:
        print("🔴 有变异没红: %s" % failures)
        return 1
    print("✅ 三条变异各自红在自己该守的断言上")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
