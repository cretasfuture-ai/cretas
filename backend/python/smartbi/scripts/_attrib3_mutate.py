# -*- coding: utf-8 -*-
"""③⑤ 的变异对照 —— **先证明行为变了，再看断言红不红**。

🔴 本仓踩过三次的形状：变异之后断言不红，可能是两个**相反**的结论
   （断言在守空气 / 变异根本没打进去）。⇒ 每条变异必须先打印一个
   `BEHAVIOUR` 读数，证明被测行为真的换了；行为没换的那条**不作数**。

⚠️ 文本模式 + `newline=''` —— ⛔ 不用 bytes 字面量（中文注释进不去），
   也不做行尾转换（Windows 上 `write_text` 会把整文件转 CRLF，本仓两个 PR 因此被打回）。
⚠️ 改文件的命令**不与任何别的命令同格**：`open(...,'w')` 在表达式求值之前就截断文件。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SE = ROOT / "smartbi" / "agent" / "synthesis_engine.py"
FB = ROOT / "smartbi" / "agent" / "factbook.py"
QU = ROOT / "smartbi" / "gold" / "queries.py"
TEST = "tests/test_traffic_trend_normalises_by_operating_days.py"
CATALOG_TEST = "tests/test_investigation_directions_only_name_what_we_have.py"

#: 每条变异：(名字, 文件, 原串, 变异串, 期望变红的断言, 行为探针表达式)
#:
#: `行为探针` 打印一个**能看出行为换了**的值。⛔ 不打印「替换成功」——
#: 那只证明文本被改了。
MUTATIONS = [
    (
        "M1 按总额比，不除以天数",
        FB,
        "        cur_pd = c[\"bills\"] / c[\"days\"] if c[\"days\"] else 0.0\n"
        "        prev_pd = p[\"bills\"] / p[\"days\"]",
        "        cur_pd = float(c[\"bills\"])\n"
        "        prev_pd = float(p[\"bills\"])",
        f"{TEST}::TestStep3NormalisesByOperatingDays",
        "laggard_bills_pct",
    ),
    (
        "M2 拿掉「上一周期天数不够」那道闸",
        FB,
        "    if not prev or prev_days < MIN_DAYS_FOR_TREND:",
        "    if False:",
        f"{TEST}::TestStep3RefusesInsteadOfFabricating",
        "status_when_prev_has_3_days",
    ),
    (
        "M3 删掉 ⑤a 那一行（盲区披露）",
        SE,
        "        lines.append(\"- \" + _reality_check_line(factbook))\n",
        "",
        f"{TEST}::TestStep5aAdmitsTheBlindSpot",
        "blind_spot_in_block",
    ),
    (
        "M4 ⑤b 无条件触发（拿掉阈值判据）",
        SE,
        "    if not traffic_drop_warrants_asking(trend):",
        "    if False:",
        f"{TEST}::TestStep5bOnlyAsksWhenTheTrafficReallyMoved",
        "line_for_flat_tenant_names_a_store",
    ),
    (
        "M5 不取前一个等长窗（③ 的取数断线）",
        SE,
        "                tasks[\"attribution_prev\"] = _safe(",
        "                _unused_prev = (",
        f"{TEST}::TestItIsComputedInExactlyOnePlace",
        "prev_task_registered",
    ),
    (
        "M6 not_comparable 不渲染（沉默）",
        FB,
        "        if tt.get(\"status\") == \"not_comparable\":\n"
        "            lines.append(\"## 客流趋势（同比/环比）\")",
        "        if False:\n"
        "            lines.append(\"## 客流趋势（同比/环比）\")",
        f"{TEST}::TestStep3ReachesThePrompt",
        "not_comparable_reaches_prompt",
    ),
    (
        "M7 store_comparison 不再吐 daysWithData",
        QU,
        "            \"daysWithData\": days_with_data,\n",
        "",
        f"{TEST}::TestTheProducerReallyEmitsWhatTheConsumerReads",
        "days_present_in_store_row",
    ),
    (
        "M8 ⑤a 恢复粗体（会请他去点一个不存在的维度）",
        SE,
        "\"上面这些都查过、还是解释不了，再想一下门口这段时间有没有变化：\"",
        "\"上面这些都查过、还是解释不了，再想一下**门口这段时间有没有变化**：\"",
        f"{CATALOG_TEST}::TestItOnlyNamesDimensionsWeActuallyHave "
        f"{TEST}::TestStep5bOnlyAsksWhenTheTrafficReallyMoved",
        "bold_in_blind_spot",
    ),
    (
        "M9 趋势数不进 facts_index（派生数没有名字就守不住）",
        FB,
        "                    (\"拖后腿门店客流变化率\", lag.get(\"bills_pct\")),",
        "                    # 变异: 这个数不再有名字",
        f"{TEST}::TestStep3ReachesThePrompt",
        "trend_rate_is_grounded",
    ),
    (
        "M10 ③ 的数不再确定性渲染（退回让模型说）",
        SE,
        "        answer = self._append_traffic_trend(answer, factbook)\n",
        "",
        f"{TEST}::TestStep3IsRenderedDeterministicallyNotNarrated",
        "trend_numbers_reach_the_boss",
    ),
    (
        "M11 天数不同那一句不说（老板无从判断那几天是关店还是没上传）",
        SE,
        "        if trend.get(\"days_differ\"):",
        "        if False:",
        f"{TEST}::TestStep3IsRenderedDeterministicallyNotNarrated",
        "day_counts_stated_to_boss",
    ),
    (
        "M12 prompt 里把数字还给模型（第一版的形状，prod 上多说了一个错数）",
        FB,
        "                f\"（按各周期实际营业天数折算）：{verdict}。\"",
        "                f\"：每天 {lag.get('bills_per_day')} 单，\"\n"
        "                f\"上一周期每天 {lag.get('prev_bills_per_day')} 单。\"",
        f"{TEST}::TestStep3IsRenderedDeterministicallyNotNarrated",
        "prompt_block_has_digits",
    ),
]

_PROBE = r'''
import sys, os
# ⚠️ 两条 sys.path —— 第二条 `smartbi` 才是关键（`services` 在它下面）。
# 不加它 `No module named 'services'`，而那个症状和「我的变异把代码弄坏了」
# 长得一模一样（见 _probe_bootstrap 的第 1 件套）。
sys.path.insert(0, r"{root}")
sys.path.insert(0, os.path.join(r"{root}", "smartbi"))
from smartbi.agent import synthesis_engine as se
from smartbi.agent.factbook import FactBook, compute_store_attribution, compute_traffic_trend

CUR = [("A", 2119088.52, 5978), ("B", 2176848.82, 5978), ("C", 2133285.23, 5975)]
PREV = [("A", 1528173.43, 4200), ("B", 1488453.74, 4200), ("C", 1523885.22, 4200)]

def rows(src, days):
    return [{{"name": n, "revenue": r, "orderCount": b, "daysWithData": days}}
            for n, r, b in src]

t = compute_traffic_trend(rows(CUR, 30), rows(PREV, 21), laggard_name="A")
lag = (t.get("laggard") or {{}})
print("laggard_bills_pct=", lag.get("bills_pct"))

t3 = compute_traffic_trend(rows(CUR, 30), rows(PREV, 3), laggard_name="A")
print("status_when_prev_has_3_days=", t3.get("status"))

fb = FactBook()
fb.attribution = compute_store_attribution(rows(CUR, 30))
fb.traffic_trend = t
fb.available_dimensions = []
fb.missing_dimensions = [{{"code": "channel", "label": "堂食/外卖/自提"}}]
block = se.ComprehensiveSynthesisEngine._append_investigation_directions("原文。", fb)
print("blind_spot_in_block=", "只有你和店长知道" in block)
print("line_for_flat_tenant_names_a_store=", "A 的客流是真的掉了" in se._reality_check_line(fb))
print("bold_in_blind_spot=", "**" in se._REALITY_CHECK_BLIND_SPOT)

import ast, inspect
tree = ast.parse(inspect.getsource(se))
keys = [n.slice.value for n in ast.walk(tree)
        if isinstance(n, ast.Subscript) and isinstance(n.value, ast.Name)
        and n.value.id == "tasks" and isinstance(n.slice, ast.Constant)]
print("prev_task_registered=", keys.count("attribution_prev"))

nc = compute_traffic_trend(rows(CUR, 30), [])
fb2 = FactBook(); fb2.traffic_trend = nc
print("not_comparable_reaches_prompt=", "算不出客流趋势" in "\n".join(fb2.to_prompt_lines()))
print("trend_rate_is_grounded=", "拖后腿门店客流变化率" in fb.to_facts_index())

_ans = se.ComprehensiveSynthesisEngine._append_traffic_trend("原答案。", fb)
# 🔴 M10 拆的是**接线**不是函数：直调这个函数永远读得到数，
#    所以「数进没进老板那一屏」必须数 `synthesize` 里的调用点
#    （第一版探针直调函数 ⇒ 读出「变异没到达」，而它其实到达了）。
_wire = [n for n in ast.walk(tree)
         if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute)
         and n.func.attr == "_append_traffic_trend"]
print("trend_numbers_reach_the_boss=",
      (("199.3" in _ans) or ("199.2" in _ans)) and len(_wire) == 2)
print("day_counts_stated_to_boss=", ("30 天" in _ans) and ("21 天" in _ans))
# ⚠️ 到**空行**为止，⛔ 不切固定行数 —— 固定行数会把下一段的数字算进来。
_pl = fb.to_prompt_lines()
_st = next(i for i, ln in enumerate(_pl) if ln.startswith("## 客流趋势"))
_blk = []
for _ln in _pl[_st:]:
    if _ln == "" and _blk:
        break
    _blk.append(_ln)
print("prompt_block_has_digits=", any(ch.isdigit() for ch in "\n".join(_blk)))

# 🔴 ⛔ 不用 inspect.getsource 数文本 —— `store_comparison` 的 **docstring 里
# 也写着 daysWithData**，第一版探针因此读出「变异没到达」（本仓第四次同形：
# grep 把注释/文档串也数了进去）。改成数 AST 里真正的 dict **键**。
import ast as _ast, inspect as _i
from smartbi.gold import queries as q
_tree = _ast.parse(_i.getsource(q.store_comparison))
_keys = [k.value for d in _ast.walk(_tree) if isinstance(d, _ast.Dict)
         for k in d.keys if isinstance(k, _ast.Constant)]
print("days_present_in_store_row=", "daysWithData" in _keys)
'''


def read_behaviour(root: Path) -> dict:
    src = _PROBE.format(root=str(root).replace("\\", "\\\\"))
    proc = subprocess.run([sys.executable, "-c", src], cwd=str(root),
                          capture_output=True, text=True, encoding="utf-8")
    out = {}
    for line in (proc.stdout or "").splitlines():
        if "=" in line:
            k, _, v = line.partition("=")
            out[k.strip()] = v.strip()
    if not out:
        out["<探针起不来>"] = (proc.stderr or "")[-400:]
    return out


def run_tests(root: Path, targets: str) -> tuple:
    proc = subprocess.run(
        [sys.executable, "-m", "pytest", *targets.split(), "-q", "--no-header"],
        cwd=str(root), capture_output=True, text=True, encoding="utf-8")
    tail = [ln for ln in (proc.stdout or "").splitlines() if "passed" in ln or "failed" in ln]
    return proc.returncode, (tail[-1] if tail else "<没有汇总行>")


def main() -> int:
    root = ROOT
    base_behaviour = read_behaviour(root)
    print("=" * 78)
    print("基线行为读数:")
    for k, v in base_behaviour.items():
        print(f"  {k} = {v}")
    base_rc, base_line = run_tests(root, f"{TEST} {CATALOG_TEST}")
    print(f"基线测试: rc={base_rc}  {base_line}")
    if base_rc != 0:
        print("rc=2 基线就是红的 —— 变异对照无意义", file=sys.stderr)
        return 2

    results = []
    for name, path, old, new, targets, probe_key in MUTATIONS:
        with open(path, "r", encoding="utf-8", newline="") as fh:
            src = fh.read()
        n = src.count(old)
        if n != 1:
            results.append((name, f"⛔ 变异串出现 {n} 次（应为 1），本条作废", "-", "-"))
            continue
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(src.replace(old, new, 1))
        try:
            after = read_behaviour(root)
            before_v = base_behaviour.get(probe_key, "<无>")
            after_v = after.get(probe_key, "<无>")
            reached = before_v != after_v
            rc, line = run_tests(root, targets)
        finally:
            with open(path, "w", encoding="utf-8", newline="") as fh:
                fh.write(src)
        results.append((
            name,
            f"{probe_key}: {before_v} → {after_v} "
            + ("✅到达" if reached else "🔴没到达 ⇒ 本条不作数"),
            "🔴 红了" if rc != 0 else "⚠️ 仍然绿",
            line,
        ))

    print("=" * 78)
    print("变异逐条表")
    bad = 0
    for name, reach, verdict, line in results:
        print(f"\n{name}")
        print(f"  变异到达: {reach}")
        print(f"  断言    : {verdict}   {line}")
        if "没到达" in reach or "仍然绿" in verdict or "作废" in reach:
            bad += 1

    rc_now, line_now = run_tests(root, f"{TEST} {CATALOG_TEST}")
    print(f"\n还原之后重跑: rc={rc_now}  {line_now}")
    if rc_now != 0:
        print("rc=2 还原之后没回到绿 —— 变异脚本自己弄坏了树", file=sys.stderr)
        return 2
    print(f"\n有问题的条目: {bad}/{len(results)}")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
