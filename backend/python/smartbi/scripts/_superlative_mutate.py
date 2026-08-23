# -*- coding: utf-8 -*-
"""变异对照: 逐条破坏「被守的行为」，看该红的那条断言红不红。

## 纪律（本轮全部照做，⛔ 不许省）

1. 每条变异**先证明它真的生效了** —— 锚点恰好 1 次 / 落盘 / `compile()` 通过。
   变异不红有两个相反成因（断言没用 / 变异没打进去），症状一模一样。
2. ⛔ 红在 `NameError` / `SyntaxError` 上的变异不算数 —— 所以每条都先编译。
3. 🔴 判据是一条 6 项的 AND 链。新增条件会让别的条件被短路成恒真式，
   所以**每条变异都跑全部 10 条断言**，逐条记录谁红了。
4. 变异打在**被守的行为**上：每条对照都写明「这条断言在守什么」。
5. 文本模式 + `newline=''` —— ⛔ 不用 bytes 字面量（中文注释进不去），
   也不用 `write_text`（Windows 上会把整文件转 CRLF）。
"""
from __future__ import annotations

import compileall  # noqa: F401  (显式说明: 我们用 compile() 而不是它)
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PY_ROOT = os.path.dirname(os.path.dirname(HERE))
SUPERLATIVE_TARGET = os.path.join(
    PY_ROOT, "smartbi", "gold", "restaurant", "restaurant_intent.py")
SUPERLATIVE_TESTS = os.path.join(
    PY_ROOT, "smartbi", "gold", "tests", "test_superlative_store_reference.py")

#: (名字, 这条变异破坏的**行为**, 锚点, 替换串, 预期变红的断言)
SUPERLATIVE_MUTATIONS = (
    (
        "M1_不看模型的维度",
        "守的行为: 只有模型自己判定「按门店拆」时才封定门店范围",
        '        "store" in tuple(dimensions or ())\n',
        '        True  # MUTATION M1\n',
        "test_对照1_没有门店维度时反问必须留着",
    ),
    (
        # ⚠️ 这一条**换过靶子**。第一版打的是 `and not store_names`, 变异后
        #    **一条都没红** —— 而那不是「断言在守空气」, 是**变异没改变行为**:
        #    上游 `if store_names and not store_scope: store_scope = "single"`
        #    已经把 scope 定成 single, 我这里的 `not store_scope` 先短路了。
        #    ⇒ 承重的是 `not store_scope`, 靶子挪到它身上。
        #    (`not store_names` 保留为冗余防线, 已在产品代码里显式登记。)
        "M2a_只去掉not_store_scope",
        "预期**不红**: 两条互为备份 —— 去掉一条另一条还拦得住(见 M2b)",
        "        and not store_scope\n        # ⚠️ 登记: `not store_names` 是**冗余防线**",
        "        and True  # MUTATION M2a\n        # ⚠️ 登记: `not store_names` 是**冗余防线**",
        "",   # 空 = 预期不红
    ),
    (
        # 🔴 这一条是「往 AND 链里加条件会让别的条件变成恒真式」的现场:
        #    `not store_scope` 和 `not store_names` **互相短路**, 单独去掉任何
        #    一条, 10 条断言一条都不红。要让被守的行为真的坏掉必须同时去掉两条。
        #    ⛔ 记成「断言在守空气」是错的 —— 它守得住, 只是有两道锁。
        "M2b_两条同时去掉",
        "守的行为: 老板点名了一家店, 范围就是那一家, ⛔ 不许改写成全部门店",
        ("        and not store_scope\n        # ⚠️ 登记: `not store_names` 是**冗余防线**",
         "        and not store_names\n        # 查: 本租户真的有多家店可排序"),
        ("        and True  # MUTATION M2b-1\n        # ⚠️ 登记: `not store_names` 是**冗余防线**",
         "        and True  # MUTATION M2b-2\n        # 查: 本租户真的有多家店可排序"),
        "test_对照2_模型点名了门店时不许被改写成全部门店",
    ),
    (
        "M3_不核对resolver能力",
        "守的行为: 能力表说这个 resolver 出不了按门店的数时, 逐字维持既有行为",
        '        and _resolver_serves_dimensions(code, ("store",))\n'
        "        and not is_continuation\n",
        '        and True  # MUTATION M3\n'
        "        and not is_continuation\n",
        "test_对照3_resolver服务不了store维度时逐字维持既有行为",
    ),
    (
        "M4_不查门店目录条数",
        "守的行为: 单店租户不在这里抢答, 交给下游推 single",
        "        and len(tuple(available_stores or ())) > 1\n",
        "        and len(tuple(available_stores or ())) >= 0  # MUTATION M4\n",
        "test_对照4_单店租户逐字维持既有行为",
    ),
    (
        "M5_延续轮也抢答",
        "守的行为: 延续轮里老板正在回答槽位, 替他答就是抢答(按钮链)",
        '        and _resolver_serves_dimensions(code, ("store",))\n'
        "        and not is_continuation\n    ):",
        '        and _resolver_serves_dimensions(code, ("store",))\n'
        "        # MUTATION M5\n    ):",
        "test_对照6_澄清延续轮逐字维持既有行为",
    ),
    (
        "M6_无条件清掉澄清",
        "守的行为: 还缺指标时反问必须留着 —— 指标没有无歧义的默认值",
        "        if clarification_needed and not missing_fields:",
        "        if clarification_needed:  # MUTATION M6",
        "test_对照5_还缺指标时反问必须留着",
    ),
    (
        "M7_整块不生效",
        "守的行为: 正向 —— 计算式指代不再被问「是指哪家店」",
        '        "store" in tuple(dimensions or ())\n',
        '        False  # MUTATION M7\n',
        "test_计算式指代不再被问是指哪家店 / test_模型没说缺门店时同样把按门店拆读成全部门店",
    ),
)


def _read() -> str:
    with open(SUPERLATIVE_TARGET, "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def _write(text: str) -> None:
    with open(SUPERLATIVE_TARGET, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def _run_tests() -> tuple[int, list[str]]:
    proc = subprocess.run(
        [sys.executable, "-m", "pytest", SUPERLATIVE_TESTS,
         "-p", "no:cacheprovider", "-q", "--tb=line"],
        cwd=PY_ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace",
    )
    out = (proc.stdout or "") + (proc.stderr or "")
    reds = sorted({
        line.split("::", 1)[1].split("[")[0]
        for line in out.splitlines()
        if line.startswith("FAILED ") and "::" in line
    })
    # ⛔ NameError / SyntaxError 上的红不算数
    poisoned = [k for k in ("NameError", "SyntaxError", "IndentationError")
                if k in out]
    return len(reds), reds, poisoned  # type: ignore[return-value]


def main() -> int:
    original = _read()
    print("=" * 76)
    print("基线: 未变异时应当 10/10 全绿")
    n, reds, poisoned = _run_tests()  # type: ignore[misc]
    print("  红 %d 条 %s  中毒=%s" % (n, reds, poisoned))
    if n != 0:
        print("⛔ rc=2 基线不是全绿, 后面每一条变异读数都无意义")
        return 2

    verdicts = []
    for name, guards, anchor, repl, expect in SUPERLATIVE_MUTATIONS:
        print("\n" + "=" * 76)
        print("%s —— %s" % (name, guards))
        # ① 先证明**每个**锚点都恰好命中 1 次 —— 0 次或多次都让读数无意义
        anchors = anchor if isinstance(anchor, tuple) else (anchor,)
        repls = repl if isinstance(repl, tuple) else (repl,)
        hits = [original.count(a) for a in anchors]
        print("  锚点命中 = %s 次" % hits)
        if hits != [1] * len(anchors):
            print("  ⛔ rc=2 锚点不唯一, 这条变异作废")
            verdicts.append((name, "锚点不唯一", expect, []))
            continue
        mutated = original
        for a, r in zip(anchors, repls):
            mutated = mutated.replace(a, r, 1)
        # ② 先编译, 再落盘 —— ⛔ 红在 SyntaxError 上的变异不算数
        try:
            compile(mutated, SUPERLATIVE_TARGET, "exec")
        except SyntaxError as exc:
            print("  ⛔ rc=2 变异后语法错: %s" % exc)
            verdicts.append((name, "语法错", expect, []))
            continue
        _write(mutated)
        # ③ 证明变异**真的落在磁盘上**了
        on_disk = _read()
        landed = all(
            r.strip().splitlines()[0].strip() in on_disk for r in repls)
        print("  变异已落盘 = %s" % landed)
        if not landed:
            _write(original)
            print("  ⛔ rc=2 没落盘")
            verdicts.append((name, "没落盘", expect, []))
            continue
        try:
            n, reds, poisoned = _run_tests()  # type: ignore[misc]
        finally:
            _write(original)
        print("  🔴 变红 %d 条: %s" % (n, reds))
        print("  中毒(NameError/SyntaxError) = %s" % poisoned)
        verdicts.append((name, "ok" if not poisoned else "中毒", expect, reds))

    print("\n" + "=" * 76)
    print("变异对照汇总（⛔ 逐行）")
    ok = 0
    for name, status, expect, reds in verdicts:
        if not expect:
            hit = not reds          # 预期不红
        else:
            hit = any(e.strip() in r or r in e
                      for e in expect.split("/") for r in reds)
        ok += 1 if (status == "ok" and hit) else 0
        print("  %-24s 状态=%-8s 变红=%d 条  点名命中=%s" % (
            name, status, len(reds), "✅" if hit else "❌"))
        print("      预期红: %s" % (expect or "（预期不红）"))
        print("      实际红: %s" % (reds or "（无）"))
    print("\n%d/%d 条变异被点名的断言接住" % (ok, len(SUPERLATIVE_MUTATIONS)))
    # 收尾: 确认文件已还原
    print("文件已还原 = %s" % (_read() == original))
    return 0 if ok == len(SUPERLATIVE_MUTATIONS) else 1


if __name__ == "__main__":
    raise SystemExit(main())
