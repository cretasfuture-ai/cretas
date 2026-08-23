"""刀1 的**确定性** A/B —— ⛔ 不过 LLM，所以 100% 可复现。

## 为什么需要这一份（端到端不够）

端到端那份 A/B（`_lex_ab_probe`）量的是「老板看到的原文」，那是最终判据 ——
但它**过 LLM**：只有当模型把繁体词填进 dish/store 槽位时，本文件这几张词表才
被调用。2026-08-20 实测那一窗里模型**没有**这么填，于是 6 条繁体问句两臂逐字
相同 —— ▎那不是「改动没用」，是「这一窗里那条路没被走到」。

⇒ 两份要一起读：
   · 本份回答「**这些词表的行为变了没有**」（确定性，必然可复现）
   · 端到端那份回答「**在今天这一窗里老板看到的变了没有**」（受 LLM 抖动支配）

⛔ 只拿其中一份下结论都会错：只看端到端 → 「改动无效」；只看确定性 →
   「已经修好了」。两句都不真。

## 用法

    python _lex_deterministic_ab.py <base树> <new树>

⚠️ 两棵树用**同一段**代码加载（`importlib` 旁路），⛔ 不一臂 import 一臂旁路。
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys

#: 路径自举 —— ⛔ 不靠「记得设 PYTHONPATH」，见 `_probe_bootstrap` 的同一条教训。
_HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
for _p in (_HERE, os.path.join(_HERE, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)


def load_router(path: str, alias: str):
    """把某一棵树里的 router 单独加载成一个模块。

    ⚠️ 它 import 了 `smartbi.gold.restaurant.metric_registry` / `provenance`，
       那两个在两臂之间没有差异，从当前 sys.path 解析即可。
    """
    spec = importlib.util.spec_from_file_location(alias, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[alias] = mod
    spec.loader.exec_module(mod)
    return mod


#: (标签, 简体, 繁体) —— 每对只差简繁，⛔ 不差别的字。
PAIRS = [
    ("门店-营收",     "最近30天门店的营收是多少",   "最近30天門店的營收是多少"),
    ("单品-销量",     "最近30天单品的销量排名",     "最近30天單品的銷量排名"),
    ("整体-毛利率",   "最近30天整体的毛利率是多少", "最近30天整體的毛利率是多少"),
    ("全部门店-菜品毛利", "全部门店毛利最低的菜品", "全部門店毛利最低的菜品"),
    ("各门店-拆分",   "各门店的营收对比",           "各門店的營收对比"),
    ("哪家店-菜",     "哪家店的米饭卖得最好",       "哪家店的米飯卖得最好"),
    ("单店-提及",     "单店的营收",                 "單店的營收"),
    ("客单价-对比",   "本月客单价比上个月高还是低", "本月客單價比上個月高还是低"),
    ("损耗-金额",     "损耗价值最高的食材",         "损耗價值最高的食材"),
    ("领料-成本",     "领料成本多少",               "領料成本多少"),
    ("上个月-窗口",   "上个月营收",                 "上個月營收"),
]


def readings(R, q):
    """把这句话在**本文件那几张词表**上的确定性判读全部取出来。

    ⛔ 不判「对不对」，只把读数打出来 —— 对不对由两臂对比说话。
    """
    return {
        "dish_candidate": R.extract_dish_candidate(q),
        "dish_candidates": list(R.extract_dish_candidates(q) or []),
        "store_mentions": list(R.extract_store_mentions(q) or []),
        "store_dish_split": R.store_dish_split_dish(q),
        "sales_period_cmp": R._is_explicit_sales_period_comparison(q),
        "all_store_dish_margin": R._all_store_scope_dish_margin(q),
        "wastage_axis": R._wastage_rank_axis(q),
        "generic_tail": R._generic_tail_consumable(q),
    }


def main() -> int:
    base = load_router(sys.argv[1], "_lex_router_base")
    new = load_router(sys.argv[2], "_lex_router_new")

    # ── 阳性对照：两臂真的是**两份不同的代码** ────────────────────────
    #    ⛔ 少了这一条，「两臂读数全同」分不清「改动无效」和「我加载了同一个文件」。
    marker = (len(base._DISH_GENERIC_TOKENS), len(new._DISH_GENERIC_TOKENS))
    print("阳性对照·两臂确实不同: len(_DISH_GENERIC_TOKENS) base=%d new=%d" % marker)
    if marker[0] == marker[1]:
        print("🔴 两臂标记相同 —— 可能加载了同一份文件，读数作废 (rc=2)")
        return 2

    n_pairs_fixed = 0
    n_simplified_changed = 0
    print()
    for label, simp, trad in PAIRS:
        b_s, b_t = readings(base, simp), readings(base, trad)
        n_s, n_t = readings(new, simp), readings(new, trad)

        # ① 简体那一侧**必须逐字不变** —— 这是本刀最重要的阴性对照:
        #    补繁体不许动简体的任何行为。
        simp_same = b_s == n_s
        if not simp_same:
            n_simplified_changed += 1

        # ② 繁体侧: base 与简体不一致 ⇒ 有简繁分歧; new 与简体一致 ⇒ 分歧被抹平
        gap_before = b_t != b_s
        gap_after = n_t != n_s
        if gap_before and not gap_after:
            n_pairs_fixed += 1

        flag = ("✅修好" if (gap_before and not gap_after)
                else "—无分歧" if not gap_before
                else "🔴仍有分歧")
        print("=" * 74)
        print("【%s】%s  简体侧未变=%s" % (label, flag, simp_same))
        print("  简体 「%s」" % simp)
        print("     base %s" % json.dumps(b_s, ensure_ascii=False))
        print("     new  %s" % json.dumps(n_s, ensure_ascii=False))
        print("  繁体 「%s」" % trad)
        print("     base %s" % json.dumps(b_t, ensure_ascii=False))
        print("     new  %s" % json.dumps(n_t, ensure_ascii=False))
        if gap_before:
            diff = sorted(k for k in b_s if b_s[k] != b_t[k])
            print("  ▎base 上简繁不一致的读数: %s" % diff)
        if gap_after:
            diff = sorted(k for k in n_s if n_s[k] != n_t[k])
            print("  ▎new  上仍不一致的读数:  %s" % diff)

    print("=" * 74)
    print("口径: %d 对简繁问句 × %d 个确定性读数，只跑本文件的函数，⛔ 不过 LLM。"
          % (len(PAIRS), len(readings(new, "x"))))
    print("简繁分歧被抹平: %d 对" % n_pairs_fixed)
    print("🔴 简体侧行为被改动的对数: %d （必须是 0）" % n_simplified_changed)
    return 1 if n_simplified_changed else 0


if __name__ == "__main__":
    sys.exit(main())
