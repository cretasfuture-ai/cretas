#!/usr/bin/env python3
"""Java 全量测试的**名单棘轮** —— 已知红只许变少，⛔ 不许变多。

## 为什么需要它

2026-08-18 实测：`origin/main` 上有 **19 个红的测试方法**（8 Failures + 11 Errors），
分布在 8 个测试类里，全部集中在采购 / 销售发货 / 生产结算这几条写路径上。

它们能一直红着没人发现，是因为**没有任何自动触发会执行它们**：

```
ci.yml:121   mvn -B test      if: inputs.full_audit    ← 只有手动全量审计才跑
ci.yml:309   mvn -B verify    if: inputs.full_audit    ← 同上
ci.yml:183   mvn -B package -Dmaven.test.skip=true     ← push / PR 上跑的是这条
ci.yml:26    # full_audit stays manual -- the whole suite takes over an hour
```

⇒ **PR 上那个绿 = 编译并打包成功，⛔ 不是测试通过。**

## ⛔ 为什么是「名单」棘轮而不是「计数」棘轮

计数会掩盖：修好一个、同时弄坏一个，总数不变，闸全绿。
名单不会：新出现的那个名字不在基线里，立刻红。

## 三态退出码（与本仓硬约束 4 同源）

```
rc=0   失败集合 ⊆ 基线            没有新增红
rc=1   出现基线里没有的失败        有新增红（读数有效，且指向缺陷）
rc=2   这次没量到                 编译失败 / 一个测试都没跑 / 日志解析不出结果
```

⚠️ **`rc=2` 必须与 `rc=1` 用不同措辞告警** —— 否则「没量到」会被折叠进「没问题」，
一个连编译都过不去的跑批会安静地天天绿，而它一个测试都没看过。

## 用法

```bash
# 从 surefire 报告目录读结果, 与基线比对
python scripts/ci/java-test-ratchet.py \
    --reports backend/java/cretas-api/target/surefire-reports \
    --baseline backend/java/cretas-api/known-failing-tests.txt

# 重新生成基线（只在【确认新增的红都已修完】之后手工跑）
python scripts/ci/java-test-ratchet.py --reports ... --baseline ... --write-baseline
```
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

RC_OK, RC_NEW_FAILURES, RC_NOT_MEASURED = 0, 1, 2


def collect(reports_dir: str) -> tuple[set[str], int, set[str]]:
    """返回 (失败的 Class.method 集合, 总共跑了多少个测试, **跑过的** Class.method 全集)。

    🔴 第三个返回值是 2026-08-27 加的, 为了把这两件事分开:
         「不再失败」 —— 真的修好了(它跑了, 而且通过)
         「不再存在」 —— 被改名 / 删掉 / 禁用了(它压根没跑)
    此前两者都落进 `baseline - failing`, 一律报「✅ 已修好」——
    **那等于告诉人「删掉一个红的测试就算修好了」**。

    ⚠️ 读 surefire 的 XML，⛔ 不解析 mvn 的控制台输出 ——
    控制台会被编码问题弄成乱码（本仓实测过 GBK 日志），XML 是结构化的。
    """
    failing: set[str] = set()
    executed: set[str] = set()
    total = 0
    if not os.path.isdir(reports_dir):
        return failing, 0, executed
    for name in sorted(os.listdir(reports_dir)):
        if not (name.startswith("TEST-") and name.endswith(".xml")):
            continue
        path = os.path.join(reports_dir, name)
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        total += int(root.get("tests") or 0)
        for case in root.iter("testcase"):
            cls = case.get("classname") or ""
            method = case.get("name") or ""
            # 参数化测试的 name 带 [1] 之类的下标 —— 去掉，否则加一条用例就"新增红"
            method = method.split("[", 1)[0].strip()
            if not (cls and method):
                continue
            # ⚠️ executed 要在「是否失败」判断【之前】收 —— 它记的是「跑过」不是「跑挂了」
            executed.add(f"{cls}.{method}")
            if case.find("failure") is None and case.find("error") is None:
                continue
            failing.add(f"{cls}.{method}")
    return failing, total, executed


def read_baseline(path: str) -> set[str]:
    if not os.path.exists(path):
        return set()
    out = set()
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                out.add(line)
    return out


#: 从基线头部那句「本次共跑 N 个测试」把 N 读回来。
#: 🔴 这个数是【样本量下限】的依据 —— 见 main() 里那段注释。读不到就返回 0(不设限)。
_BASELINE_TOTAL_RE = re.compile(r"本次共跑\s*(\d+)\s*个测试")


def read_baseline_total(path: str) -> int:
    if not os.path.exists(path):
        return 0
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            if not line.lstrip().startswith("#"):
                break
            m = _BASELINE_TOTAL_RE.search(line)
            if m:
                return int(m.group(1))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reports", required=True, help="surefire-reports 目录")
    ap.add_argument("--baseline", required=True, help="已知红名单文件")
    ap.add_argument("--write-baseline", action="store_true",
                    help="用本次结果覆盖基线（⛔ 只在确认新增红已修完后手工跑）")
    ap.add_argument("--min-tests", type=int, default=100,
                    help="低于这个数就判定「没量到」（rc=2）")
    ap.add_argument("--min-ratio", type=float, default=0.9,
                    help="本次跑的测试数低于「基线记录总数 × 该比例」就判「没量到」"
                         "（rc=2）。防 fork VM 中途崩掉后拿残缺报告当有效读数。")
    args = ap.parse_args()

    failing, total, executed = collect(args.reports)

    # ── rc=2：这次没量到 ────────────────────────────────────────────
    if total < args.min_tests:
        print(f"JAVA_RATCHET INSTRUMENT_DEAD —— 只解析到 {total} 个测试"
              f"（下限 {args.min_tests}）。编译失败 / 报告目录为空 / 解析不出结果。")
        print("⚠️ 本次读数【作废】，⛔ 不代表没有新增红。")
        return RC_NOT_MEASURED

    # ── rc=2：跑了，但【只跑了一部分】────────────────────────────────
    #
    # 🔴 2026-08-26 实测, 这是本脚本此前最危险的一个洞:
    #    CI 上 surefire fork VM 在跑到第 2866 个测试时 OOM 崩掉, maven 报错退出,
    #    但**已经写出来的 2866 份报告仍在**。绝对下限是 100, 于是 2866 轻松过关,
    #    本脚本把它当成一次有效读数, 并且报出:
    #        「共跑 2866 个测试 · 红 29 · 基线 143」
    #        「✅ 已修好 124 条（可以从基线里删掉）」
    #    那 124 条**不是修好了, 是根本没跑**。若有人照这句话去删基线,
    #    124 条债就被静默勾销 —— 比首跑那次「笼统报个 job failure」危险得多,
    #    因为它**崩得像成功**。
    #
    # ⇒ 判据不能是一个魔法常数, 要跟【基线自己记下的那个总数】比。
    #    基线头部写着「本次共跑 N 个测试」, 少于 N 的 min_ratio 就是没量到。
    # ⛔ 不要因为「这次多跑/少跑几个测试很正常」就把比例调松:
    #    测试数正常波动是几十条, 而这个洞的量级是【少了 76%】。
    baseline_total = read_baseline_total(args.baseline)
    if baseline_total and total < baseline_total * args.min_ratio:
        print(f"JAVA_RATCHET INSTRUMENT_DEAD —— 只跑了 {total} 个测试, "
              f"而基线记录的是 {baseline_total} 个"
              f"（下限 {args.min_ratio:.0%} = {int(baseline_total * args.min_ratio)}）。")
        print("   多半是 fork VM 中途崩了(OOM 等), 报告只写出了一部分。")
        print("⚠️ 本次读数【作废】，⛔ 不代表没有新增红，"
              "⛔ 更不代表少掉的那些「修好了」。")
        return RC_NOT_MEASURED

    baseline_exists = os.path.exists(args.baseline)
    baseline = read_baseline(args.baseline)

    # ── 基线还没被【本 CI 环境】产出过 ──────────────────────────────
    #
    # 🔴 基线必须由**将来读它的那个仪器**产出。本地跑（没有 postgres service）
    #    和 CI 跑的失败集合必然不同 —— 拿本地的当基线，第一晚就会报出一堆
    #    根本不是新增的"新增红"，然后这道闸当天就被关掉（形态 E）。
    #
    # ⇒ 所以首次运行不红，只打印清单并告诉你怎么落基线。
    if not args.write_baseline and not baseline_exists:
        print(f"JAVA_RATCHET BASELINE_MISSING —— 基线文件不存在: {args.baseline}")
        print(f"本次共跑 {total} 个测试，其中 {len(failing)} 个红：")
        for name in sorted(failing):
            print(f"   {name}")
        print("\n⇒ 手动触发一次本 workflow 并把 write_baseline 设为 true，"
              "把产出的名单提交进仓库。")
        print("⛔ 不要用本地跑的结果当基线 —— 环境不同，失败集合必然不同。")
        return RC_OK

    if args.write_baseline:
        os.makedirs(os.path.dirname(args.baseline) or ".", exist_ok=True)
        with open(args.baseline, "w", encoding="utf-8", newline="") as fh:
            fh.write("# Java 全量测试的已知红名单（棘轮：只许变少）\n")
            fh.write("# ⛔ 不要手工往里加。修好一个就从这里删一行。\n")
            fh.write(f"# 本次共跑 {total} 个测试，其中 {len(failing)} 个红。\n")
            for name in sorted(failing):
                fh.write(name + "\n")
        print(f"JAVA_RATCHET BASELINE_WRITTEN {len(failing)} 条 / 共 {total} 个测试")
        return RC_OK

    new = sorted(failing - baseline)

    # 🔴 2026-08-27: 把「不再失败」拆成两件事。
    #
    # 此前这里是 `fixed = baseline - failing`, 于是**一个被删掉的红测试也算「已修好」**
    # —— 那等于告诉人「删掉它就行了」。实测在基线里逮到一条:
    #   MaterialConsumptionNotesAndPlanRefTest.useWritesNoConsumptionRowWithoutAPlan
    # 那个类已被改名成 MaterialConsumptionTraceContractTest, 条目成了永远匹配不上的幽灵。
    #
    # ⚠️ 这个拆分之所以安全, 是因为上面那道**样本量下限**(total < baseline_total × min_ratio
    #    ⇒ rc=2)已经把「窄选择器 / fork 半途崩了」挡在前面 ——
    #    走到这里时这一轮是基本完整的, 所以「没跑到」才等价于「不存在了」。
    fixed = sorted((baseline & executed) - failing)
    vanished = sorted(baseline - executed)

    print(f"JAVA_RATCHET 共跑 {total} 个测试 · 红 {len(failing)} · 基线 {len(baseline)}")
    if fixed:
        print(f"\n✅ 已修好 {len(fixed)} 条（跑了而且通过，可以从基线里删掉）：")
        for n in fixed:
            print(f"   {n}")
    if vanished:
        # ⛔ 措辞必须与「已修好」不同 —— 这两件事的下一步动作是相反的:
        #    修好了 → 删基线条目; 不存在了 → 先确认它是改名还是**被删掉了**。
        print(f"\n⚠️ 基线里有 {len(vanished)} 条**这次根本没跑到**（不是「修好了」）：")
        for n in vanished:
            print(f"   {n}")
        print("   可能是改名 / 移动 / 被 @Disabled / **被删掉**。")
        print("   ⛔ 删掉一个红的测试不是修好它。先确认是哪一种，再决定基线怎么改。")
    if new:
        print(f"\n🔴 新增 {len(new)} 条基线里没有的红：")
        for n in new:
            print(f"   {n}")
        print("\n⛔ 这些是这次改动引入的。修掉它们，"
              "⛔ 不要把它们加进基线 —— 基线只许变少。")
        return RC_NEW_FAILURES

    print("\n没有新增红。")
    return RC_OK


if __name__ == "__main__":
    sys.exit(main())
