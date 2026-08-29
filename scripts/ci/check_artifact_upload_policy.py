#!/usr/bin/env python3
"""制品上传策略闸 —— 诊断制品不许判死闸；产物制品必须判死闸。

## 它守的那次事故（2026-08-29）

GitHub 的制品存储配额打满：

    Failed to CreateArtifact: Artifact storage quota has been hit.
    Unable to upload any new artifacts. Usage is recalculated every 6-12 hours.

于是 `java-full-gate` **连续三个 PR 报 fail**，而它真正在守的两步

    5  success  Run the full Java suite
    6  success  Ratchet — 已知红只许变少
    7  failure  上传本次的失败名单（便于重新落基线）   <- 只有这一步
    8  success  基线不许吞掉窄闸原本覆盖的类

全是绿的。更糟的是它后面那两步「判定」被 `skip` 掉了 ——
**这道闸连自己的结论都说不出来**，只剩一个红叉。

⚠️ 而这个红叉会训练人「java-full-gate 又红了，不用看」——
   下次它因为**真的有新增红**而红时，同样没人看。
   一道天天误报的闸，最终结局是被忽略；那时它的覆盖率归零。

## 规则（两个方向都要守）

- **诊断类**（失败名单 / surefire 报告 / playwright 截图 / 日志 / 覆盖率）
  必须带 `continue-on-error: true`。它是给人事后查的，**不是判据**。
- **产物类**（部署要取回的 jar、web dist）**不许**带。
  那个制品就是产出本身，传不上去就是真的失败。

⛔ 只守一个方向是不够的：把产物上传也放开，会让「制品没传上去」变成静默，
   而部署那一侧要到取不到文件时才发现。

## 三态（本仓硬约束 4）

    0  合规
    1  有违规
    2  这次没量到（一个 workflow 都没读到 / 一个上传步骤都没找到）
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# 产物类：上传失败**就是**失败。登记在这里的文件不许带 continue-on-error。
# ⛔ 往这里加一行必须写清「谁会去取回这个制品」。
PRODUCT_WORKFLOWS: dict[str, str] = {
    "java-artifact.yml": "部署侧按 cretas-java-<sha> 取回这个 jar；传不上去部署就没有东西可用",
    "web-dist.yml": "部署侧按 cretas-web-<sha> 取回 dist；传不上去 web-admin 发不出去",
}

UPLOAD_RE = re.compile(r"^(?P<indent>\s*)uses:\s*actions/upload-artifact@")
CONTINUE_RE = re.compile(r"^\s*continue-on-error:\s*true\s*$")
STEP_START_RE = re.compile(r"^\s*-\s")


def find_upload_steps(lines: list[str]) -> list[tuple[int, bool]]:
    """返回 [(uses 所在行号(1 起), 这一步有没有 continue-on-error), ...]

    做法：从 `uses:` 那一行往回走到本步骤的起点（`- ` 开头那一行），
    途中看有没有 `continue-on-error: true`。
    ⛔ 不用「往回看固定 N 行」——步骤长度不固定，那种写法会漏也会误判。
    """
    found = []
    for i, line in enumerate(lines):
        m = UPLOAD_RE.match(line)
        if not m:
            continue
        has_continue = False
        j = i
        while j >= 0:
            if CONTINUE_RE.match(lines[j]):
                has_continue = True
                break
            if STEP_START_RE.match(lines[j]) and j != i:
                break
            j -= 1
        found.append((i + 1, has_continue))
    return found


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".")
    args = ap.parse_args()

    wf_dir = Path(args.repo) / ".github" / "workflows"
    if not wf_dir.is_dir():
        print("✗ 找不到 .github/workflows —— 本次没量到")
        return 2

    files = sorted(p for p in wf_dir.glob("*.yml"))
    if not files:
        print("✗ workflows 目录里一个 .yml 都没有 —— 本次没量到")
        return 2

    total_uploads = 0
    violations: list[str] = []
    scanned = 0

    for p in files:
        lines = p.read_text(encoding="utf-8").splitlines()
        steps = find_upload_steps(lines)
        if not steps:
            continue
        scanned += 1
        is_product = p.name in PRODUCT_WORKFLOWS
        for lineno, has_continue in steps:
            total_uploads += 1
            if is_product and has_continue:
                violations.append(
                    f"{p.name}:{lineno}  产物制品**不许**带 continue-on-error —— "
                    f"{PRODUCT_WORKFLOWS[p.name]}"
                )
            elif not is_product and not has_continue:
                violations.append(
                    f"{p.name}:{lineno}  诊断制品缺 continue-on-error —— "
                    f"制品配额一满, 这一步会把整道闸判成红, 而它守的东西全是绿的。"
                    f"如果它其实是产物制品, 请登记进 PRODUCT_WORKFLOWS 并写清谁去取回它。"
                )

    # 阳性对照：一个上传步骤都没找到，说明扫描器瞎了，此时「0 违规」没有意义。
    if total_uploads == 0:
        print("✗ 全仓 workflows 里一个 upload-artifact 步骤都没找到 —— 扫描器可能坏了, 本次没量到")
        return 2

    print(f"扫了 {len(files)} 个 workflow, 其中 {scanned} 个含上传步骤, 共 {total_uploads} 处")
    print(f"产物类登记: {', '.join(sorted(PRODUCT_WORKFLOWS))}")

    if violations:
        print()
        print(f"✗ {len(violations)} 处不合规:")
        for v in violations:
            print(f"    {v}")
        return 1

    print("✓ 诊断制品都不判死闸; 产物制品都判死闸")
    return 0


if __name__ == "__main__":
    sys.exit(main())
