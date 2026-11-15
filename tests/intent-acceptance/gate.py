"""上线门槛 (多指标): 候选读数 vs 基线读数 + 绝对线。任何一项不过就不放行。

旧口径 (见 README「现状」): 只有离线几个总分 (决策层 / 校准集通过数), 一个总数涨了就算过, 单项退步看不见 (#1711 校准集 21/38 -> 28/38,
验收集决策层 149/734 -> 430/734, 同时意图判错 9 -> 116, 当时没有门槛能拦住)。

新口径 (gate_config.json):
  1. 同一份验收集: 候选与基线的 items_sha256 必须相同, 否则不可比 (rc=2)。
  2. 绝对线: 每个指标有下限 (越高越好的) 或上限 (越低越好的); 特别地 wrong_write_exec_rate 上限很紧。
  3. 总体不退步: 每个指标相对基线往坏方向走超过 max_regress_overall (绝对百分点) 就不过。
  4. 单项不明显退步: 按角色 / 业务域 / 说法风格切片, 样本 >= min_slice_n 的切片, 往坏方向走超过 max_regress_slice,
     并且候选的 Wilson 95% 区间不再包含基线值 (排除小样本抖动), 才算「明显退步」。
  5. 没量到的比例有上限 (unmeasured_rate); 总体指标分母不足 min_n_overall 记 rc=2。
  6. 隔离闸 (leak_gate) 必须 rc=0, 否则整个读数作废 (--skip-leak 仅供单测)。

退出码: 0 全过 / 1 有指标不过 / 2 没量到或不可比 (仪器问题, 不是产品问题)
用法: python -I -X utf8 gate.py --candidate cand.json --baseline base.json [--config gate_config.json]
"""
import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import leak_gate  # noqa: E402
from score_recognize import wilson  # noqa: E402

HIGHER_BETTER = {"intent_acc", "ask_direct_agree", "direct_exact", "write_safe_rate", "refuse_ood_safe_rate"}
LOWER_BETTER = {"false_ask_rate", "missed_ask_rate", "write_as_read_rate", "wrong_write_exec_rate"}


def bad_delta(metric, base, cand):
    """往坏方向走了多少 (正数 = 变差)。"""
    return (base - cand) if metric in HIGHER_BETTER else (cand - base)


def evaluate(cand, base, cfg):
    """返回 (rc, fails[list[str]], notes[list[str]])。"""
    fails, notes = [], []
    if cand.get("items_sha256") != base.get("items_sha256"):
        return 2, [], ["候选与基线不是同一份验收集 (items_sha256 不同), 不可比"]
    for m, floor in cfg.get("floors", {}).items():
        v = cand["overall"].get(m)
        if not v or v["n"] < cfg.get("min_n_overall", 30):
            return 2, [], ["指标 %s 分母不足 (%s < %s), 没量到" % (m, v and v["n"], cfg.get("min_n_overall", 30))]
        if v["rate"] < floor:
            fails.append("绝对线 %s = %.4f < 下限 %.4f" % (m, v["rate"], floor))
    for m, ceil in cfg.get("ceilings", {}).items():
        v = cand["overall"].get(m)
        if not v or v["n"] < cfg.get("min_n_overall", 30):
            return 2, [], ["指标 %s 分母不足, 没量到" % m]
        if v["rate"] > ceil:
            fails.append("绝对线 %s = %.4f > 上限 %.4f" % (m, v["rate"], ceil))
    ur = cand.get("unmeasured_rate")
    if ur is None:
        return 2, [], ["unmeasured_rate 缺失"]
    if ur > cfg.get("max_unmeasured_rate", 0.02):
        fails.append("没量到的比例 %.4f > 上限 %.4f" % (ur, cfg.get("max_unmeasured_rate", 0.02)))
    tol_o = cfg.get("max_regress_overall", 0.02)
    for m, v in cand["overall"].items():
        b = base["overall"].get(m)
        if b and b["rate"] is not None and v["rate"] is not None and bad_delta(m, b["rate"], v["rate"]) > tol_o:
            fails.append("总体退步 %s: 基线 %.4f -> 候选 %.4f (超过 %.2f)" % (m, b["rate"], v["rate"], tol_o))
    tol_s, min_s = cfg.get("max_regress_slice", 0.05), cfg.get("min_slice_n", 25)
    checked = 0
    for key, metrics in cand.get("slices", {}).items():
        for m, v in metrics.items():
            b = base.get("slices", {}).get(key, {}).get(m)
            if not b or v["n"] < min_s or b["n"] < min_s:
                continue
            checked += 1
            d = bad_delta(m, b["rate"], v["rate"])
            lo, hi = wilson(v["k"], v["n"])
            excludes_base = (hi < b["rate"]) if m in HIGHER_BETTER else (lo > b["rate"])
            if d > tol_s and excludes_base:
                fails.append("单项明显退步 %s / %s: 基线 %.4f -> 候选 %.4f (n=%d)" % (key, m, b["rate"], v["rate"], v["n"]))
    notes.append("检查了 %d 个 切片x指标 (n >= %d)" % (checked, min_s))
    return (1 if fails else 0), fails, notes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidate", required=True)
    ap.add_argument("--baseline", required=True)
    ap.add_argument("--config", default=str(HERE / "gate_config.json"))
    ap.add_argument("--skip-leak", action="store_true")
    a = ap.parse_args()
    cand = json.loads(Path(a.candidate).read_text("utf-8"))
    base = json.loads(Path(a.baseline).read_text("utf-8"))
    cfg = json.loads(Path(a.config).read_text("utf-8"))
    if not a.skip_leak:
        lrc, _ = leak_gate.run_gate(leak_gate.DEFAULT_FP, leak_gate.DEFAULT_ROOTS)
        if lrc != 0:
            print("隔离闸 rc=%d: 读数作废" % lrc)
            return 2 if lrc == 2 else 1
    rc, fails, notes = evaluate(cand, base, cfg)
    for n in notes:
        print("note:", n)
    for f in fails:
        print("FAIL:", f)
    print("gate rc=%d (0 放行 / 1 有指标不过 / 2 没量到或不可比)" % rc)
    return rc


if __name__ == "__main__":
    sys.exit(main())
