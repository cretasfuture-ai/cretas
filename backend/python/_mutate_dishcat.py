# -*- coding: utf-8 -*-
"""变异对照：每条断言都要能红，而且红在它守的那一件事上。

每条变异跑三步，⛔ 一步都不能省：

  1. **打进去**  文本模式 + newline=''（⛔ 不用 bytes 字面量，中文进 bytes 当场
     SyntaxError；也⛔ 不用 sed —— 会弄坏缩进）
  2. **证明它到达了被测代码**  跑一句最小探针，看那个可观测量变了没有。
     ⚠️ 变异后不红有两种成因（断言没用 / 变异没生效），不分开就会改错那一头。
  3. **看断言红不红**

用法::

    python _mutate_dishcat.py            # 全部
    python _mutate_dishcat.py M3         # 只跑一条
"""
from __future__ import annotations

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SVC = os.path.join(HERE, "smartbi", "gold", "restaurant",
                   "restaurant_intent_service.py")
ROUTER = os.path.join(HERE, "smartbi", "gold", "restaurant",
                      "restaurant_ops_router.py")
TESTS = os.path.join(HERE, "tests", "test_dish_catalogue_wiring.py")


def read(path):
    with open(path, "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


#: (编号, 文件, 旧串, 新串, 说明, 「变异到达了吗」的探针)
#:
#: 探针返回一个**可观测量**；变异前后必须不同，否则这一轮变异不作数。
MUTATIONS = [
    (
        "M1", SVC,
        """    _catalogue_token = set_dish_catalogue(
        await load_dish_catalogue(pool, factory_id))
    try:""",
        """    _catalogue_token = None
    try:""",
        "把执行入口那次目录绑定**整段删掉**"
        "（⛔ 不是 if False —— AST 断言挡不住它）",
        "svc_bind_calls",
    ),
    (
        "M6", SVC,
        """    finally:
        # 菜单目录复位 —— 见上面 `_catalogue_token` 那一格。""",
        """    finally:
        pass
    if False:
        # 菜单目录复位 —— 见上面 `_catalogue_token` 那一格。""",
        "把复位从 finally 里拿掉(泄漏给同一 task 的下一段代码)",
        "reset_in_finally",
    ),
    (
        "M2", SVC,
        """        dish_mention, catalogue_rejected_dish = dish_slot_after_catalogue_check(
            dish_mention,
            ranking_direction=getattr(spec, "ranking_direction", None),
        )""",
        """        catalogue_rejected_dish = None""",
        "把计划槽位那次目录核对整条删掉（恢复无条件采用 LLM 提名的菜名）",
        "svc_check_calls",
    ),
    (
        "M3", ROUTER,
        """    if not ranking_direction:
        return dish_mention, None
    if not _catalogue_says_not_a_dish(dish_mention):""",
        """    if not _catalogue_says_not_a_dish(dish_mention):""",
        "删掉 `ranking_direction` 那个条件 ⇒ 变成无条件摘掉菜名",
        "named_dish_kept",
    ),
    (
        "M4", ROUTER,
        """    names = _DISH_CATALOGUE.get()
    if not names:
        return _DISH_NOT_FOUND_LEGACY.format(candidate=candidate)
    if _catalogue_says_not_a_dish(candidate):""",
        """    names = _DISH_CATALOGUE.get()
    if not names:
        return _DISH_NOT_FOUND_LEGACY.format(candidate=candidate)
    if True:""",
        "让「菜单里有、只是没卖」那一支到不了 ⇒ 又开始对在售的菜说「没有找到名为…」",
        "in_menu_wording",
    ),
    (
        "M5", ROUTER,
        """            "· 是刚上架的新菜吗？先把它加进菜单，加完这里就能单独看它。\\n" """.rstrip() + "\n",
        """            "· 请核对菜名。\\n" """.rstrip() + "\n",
        "把「是不是新菜 + 怎么办」那句话删掉（退回只说「核对菜名」）",
        "new_dish_wording",
    ),
]

PROBE = r'''
import ast, inspect, sys, json, os
# ⚠️ 两条 sys.path，第二条 `smartbi` 才是关键 —— `services` 在它下面。
#    少了它 `No module named 'services'`，而本探针**会把崩溃读成「变异没到达」**
#    （第一版就是这样：五条全报「没到达」，而那是仪器死了不是读数）。
sys.path.insert(0, r"{here}")
sys.path.insert(0, os.path.join(r"{here}", "smartbi"))
from smartbi.gold.restaurant import restaurant_intent_service as S
from smartbi.gold.restaurant import restaurant_ops_router as R

def calls(func, name):
    t = ast.parse(inspect.getsource(func))
    return sum(1 for n in ast.walk(t) if isinstance(n, ast.Call)
               and (getattr(n.func, "id", None) == name
                    or getattr(n.func, "attr", None) == name))

MENU = frozenset({{"米饭", "水煮牛肉", "娃娃菜"}})
tok = R.set_dish_catalogue(MENU)
try:
    out = {{
        "svc_bind_calls": calls(S.tiered_answer, "load_dish_catalogue"),
        "svc_check_calls": calls(S.tiered_answer,
                                 "dish_slot_after_catalogue_check"),
        "named_dish_kept": R.dish_slot_after_catalogue_check(
            "香辣蟹煲", ranking_direction=None)[0],
        "in_menu_wording": R.dish_not_found_answer_text("水煮牛肉")[:24],
        "new_dish_wording": ("新菜" in R.dish_not_found_answer_text("香辣蟹煲")),
        "reset_in_finally": any(
            isinstance(n, ast.Try) and any(
                isinstance(c, ast.Call)
                and getattr(c.func, "id", None) == "reset_dish_catalogue"
                for st in n.finalbody for c in ast.walk(st))
            for n in ast.walk(ast.parse(inspect.getsource(S.tiered_answer)))),
    }}
finally:
    R.reset_dish_catalogue(tok)
print(json.dumps(out, ensure_ascii=False))
'''


def probe():
    proc = subprocess.run(
        [sys.executable, "-c", PROBE.format(here=HERE)],
        capture_output=True, text=True, cwd=HERE, encoding="utf-8",
    )
    if proc.returncode != 0:
        return {"<探针崩了>": (proc.stderr or "")[-400:]}
    import json
    return json.loads(proc.stdout.strip().splitlines()[-1])


def run_tests():
    log = os.path.join(HERE, "_mut_pytest.log")
    with open(log, "w", encoding="utf-8", newline="") as fh:
        proc = subprocess.run(
            [sys.executable, "-m", "pytest", TESTS, "-p", "no:randomly", "-q",
             "--tb=no"],
            stdout=fh, stderr=subprocess.STDOUT, cwd=HERE,
        )
    tail = read(log).strip().splitlines()[-3:]
    return proc.returncode, " | ".join(t.strip() for t in tail)


def main() -> int:
    only = sys.argv[1] if len(sys.argv) > 1 else None
    base_probe = probe()
    # 🔴 阳性对照：仪器崩了会让**每一格**都读成 None，而 None != None 为假
    #    ⇒ 五条变异全报「没到达」。第一版正是这么读出来的。
    missing = [k for _t, _p, _o, _n, _w, k in MUTATIONS if k not in base_probe]
    if missing:
        print("rc=2 变异到达探针自己坏了，读不到这些格: %s\n%s"
              % (missing, base_probe))
        return 2
    base_rc, base_tail = run_tests()
    print("基线: rc=%d  %s" % (base_rc, base_tail))
    print("基线读数: %s\n" % base_probe)
    if base_rc != 0:
        print("rc=2 基线就是红的 —— 变异读数无意义")
        return 2

    rows = []
    for tag, path, old, new, why, probe_key in MUTATIONS:
        if only and tag != only:
            continue
        src = read(path)
        if src.count(old) != 1:
            print("rc=2 [%s] 变异串在 %s 里出现 %d 次（要 1 次）—— 这一轮不作数"
                  % (tag, os.path.basename(path), src.count(old)))
            return 2
        write(path, src.replace(old, new, 1))
        try:
            got = probe()
            reached = got.get(probe_key) != base_probe.get(probe_key)
            rc, tail = run_tests()
        finally:
            write(path, src)
        rows.append((tag, why, probe_key, base_probe.get(probe_key),
                     got.get(probe_key), reached, rc, tail))
        print("[%s] %s" % (tag, why))
        print("     变异到达: %s   (%s: %r -> %r)"
              % ("✅" if reached else "🔴 没到达（这一轮不作数）",
                 probe_key, base_probe.get(probe_key), got.get(probe_key)))
        print("     测试:     %s  %s\n"
              % ("🔴 红了（对）" if rc != 0 else "❌ 还是绿的", tail))

    # 恢复后自证
    rc, tail = run_tests()
    print("=" * 88)
    print("恢复后: rc=%d  %s" % (rc, tail))
    print("=" * 88)
    print("%-5s %-8s %-8s %s" % ("变异", "到达", "红了", "说明"))
    for tag, why, _k, _b, _g, reached, mrc, _t in rows:
        print("%-5s %-8s %-8s %s"
              % (tag, "✅" if reached else "🔴否", "✅" if mrc != 0 else "❌否", why))
    bad = [r for r in rows if not r[5] or r[6] == 0]
    return 1 if (bad or rc != 0) else 0


if __name__ == "__main__":
    raise SystemExit(main())
