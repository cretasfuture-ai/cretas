# -*- coding: utf-8 -*-
"""「菜名不在菜单里先说菜单」的五条变异对照。

⛔ 三层判据，一条都不能省：
  ① 变异**真的生效了**（锚点恰好 1 次 / 落盘 / `compile()` 通过）
  ② 该红的红了，且红的是**它守的那一条**
  ③ **不该红的没红**（计划外变红 = 打在公共路径上，归因不干净）

⚠️ 红在 `NameError` / `SyntaxError` 上的变异**不算数** —— 本轮已经栽过一次：
   实现里漏 import `current_dish_catalogue`，测试红在 `NameError` 上，
   那不是「断言在守东西」，是我把代码弄坏了。
🔴 本判据是 AND 链 ⇒ 每加一条条件，把全部变异重跑一遍
   （加第三个 AND 条件后两条阴性对照变红 0 条 —— 输入被短路了）。
"""
from __future__ import annotations

import os
import subprocess
import sys

_HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_RI = os.path.join(_HERE, "smartbi", "gold", "restaurant", "restaurant_intent.py")
_ROUTER = os.path.join(_HERE, "smartbi", "gold", "restaurant",
                       "restaurant_ops_router.py")
_DISH_FIRST_TEST = "smartbi/gold/tests/test_missing_dish_is_told_before_asking_time.py"

#: (名字, 文件, 旧串, 新串, 期望变红的测试名片段)
_DISH_FIRST_MUTATIONS = [
    ("M1 删掉整个新分支（接线断开）", _RI,
     "    _catalogue_miss = _catalogue_miss_clarification(spec)\n"
     "    if _catalogue_miss is not None:",
     "    _catalogue_miss = None\n"
     "    if _catalogue_miss is not None:",
     ["test_the_fake_dish_gets_the_menu_note_not_the_time_question",
      "test_the_criterion_is_called_from_the_guard"]),
    ("M2 去掉目录判据这一半（对任何菜名都先说菜单）", _RI,
     "    if not slot or not _catalogue_says_not_a_dish(slot):",
     "    if not slot:",
     # ⚠️ fail-open 那两条**也该红** —— 它正是由 `_catalogue_says_not_a_dish`
     #    提供的（目录不可用 ⇒ 返回 False）。第一版期望里漏了它们，
     #    驱动报「计划外变红」；⛔ 那不是打在公共路径上，是我期望写窄了。
     ["test_a_dish_on_the_menu_is_let_through",
      "test_the_real_dish_still_gets_the_time_question",
      "test_the_criterion_reuses_the_existing_check",
      "test_fail_open_when_the_menu_is_unavailable",
      "test_fail_open_on_the_real_entry"]),
    ("M3 拆掉 fail-open（目录不可用时也否决）", _ROUTER,
     "    names = _DISH_CATALOGUE.get()\n"
     "    if not names or not candidate:\n"
     "        return False  # 目录不可用 → 不否决",
     "    names = _DISH_CATALOGUE.get()\n"
     "    if not candidate:\n"
     "        return False  # 目录不可用 → 不否决",
     ["test_fail_open_when_the_menu_is_unavailable",
      "test_fail_open_on_the_real_entry"]),
    ("M4 措辞自己写一份（不复用现成的）", _RI,
     "    return dish_not_found_answer_text(slot)",
     '    return f"菜单里没有「{slot}」。"',
     # ⚠️「是刚上架的新菜吗」那条出路**就在那份措辞里** ⇒ 换掉措辞它也该红。
     #    同上：第一版期望写窄了，⛔ 不是变异打在公共路径上。
     ["test_the_wording_comes_from_the_existing_producer",
      "test_the_fake_dish_gets_the_menu_note_not_the_time_question",
      "test_the_existing_wording_producer_is_reused",
      "test_a_dish_not_on_the_menu_produces_the_catalogue_note"]),
    ("M5 把新分支挪到时间反问之后（顺序反了）", _RI,
     "        missing_slot=\"dish\",",
     "        missing_slot=\"store+time\",",
     ["test_the_fake_dish_gets_the_menu_note_not_the_time_question"]),
]


def _read(p):
    return open(p, "r", encoding="utf-8", newline="").read()


def _write(p, s):
    open(p, "w", encoding="utf-8", newline="").write(s)


def _failed(out):
    return [l.split(" ", 1)[1].split(" - ")[0]
            for l in out.splitlines() if l.startswith("FAILED ")]


def _run():
    env = dict(os.environ)
    env["PYTHONPATH"] = _HERE + os.pathsep + os.path.join(_HERE, "smartbi")
    p = subprocess.run(
        [sys.executable, "-m", "pytest", _DISH_FIRST_TEST,
         "-p", "no:cacheprovider", "-q", "--tb=line"],
        cwd=_HERE, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace")
    return p.stdout + p.stderr


def main() -> int:
    originals = {_RI: _read(_RI), _ROUTER: _read(_ROUTER)}
    ok = True
    try:
        out0 = _run()
        base = _failed(out0)
        print("=" * 78)
        print("基线(无变异): 失败 %d 条 %s" % (len(base), base or ""))
        if base:
            print("⛔ rc=2 基线就不全绿，变异读数无意义")
            return 2

        for name, path, old, new, expect in _DISH_FIRST_MUTATIONS:
            src = originals[path]
            n = src.count(old)
            print("\n" + "=" * 78)
            print("【%s】" % name)
            print("  文件=%s  锚点出现 %d 次 %s"
                  % (os.path.basename(path), n, "" if n == 1 else "⛔ 必须恰好 1 次"))
            if n != 1:
                print("  ⛔ rc=2 锚点不唯一，这条变异没打进去 —— ⛔ 不许据此下结论")
                ok = False
                continue
            mutated = src.replace(old, new, 1)
            _write(path, mutated)
            try:
                compile(mutated, path, "exec")
            except SyntaxError as exc:
                print("  ⛔ rc=2 变异把代码弄成语法错(%s) —— 这条不算数" % exc)
                _write(path, src)
                ok = False
                continue
            print("  ✅ 变异已落盘且语法正常")

            out = _run()
            # ⚠️ 红在 NameError/SyntaxError 上的不算数 —— 那是把代码弄坏
            if "NameError" in out or "ImportError" in out:
                print("  ⛔ rc=2 红在 NameError/ImportError 上 —— **这条变异不算数**")
                _write(path, src)
                ok = False
                continue
            failed = _failed(out)
            print("  实际变红 %d 条:" % len(failed))
            for f in failed:
                print("      " + f.split("::")[-1])
            hit = [e for e in expect if any(e in f for f in failed)]
            missed = [e for e in expect if e not in hit]
            extra = [f for f in failed if not any(e in f for e in expect)]
            print("  期望变红: %s" % expect)
            if missed:
                print("  🔴 该红没红: %s —— 那条断言可能在守空气" % missed)
                ok = False
            if extra:
                print("  ⚠️ 计划外变红: %s —— 归因不干净"
                      % [f.split("::")[-1] for f in extra])
                ok = False
            if not missed and not extra:
                print("  ✅ 红在它守的那一条上，⛔ 没有波及别的")
            _write(path, src)

        print("\n" + "=" * 78)
        same = all(_read(p) == s for p, s in originals.items())
        print("恢复原文件: %s" % ("一致" if same else "⛔ 不一致!"))
        return 0 if ok else 1
    finally:
        for p, s in originals.items():
            if _read(p) != s:
                _write(p, s)
                print("⚠️ finally 兜底恢复了 %s" % os.path.basename(p))


if __name__ == "__main__":
    raise SystemExit(main())
