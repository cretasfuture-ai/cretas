#!/usr/bin/env python3
"""删掉**已过期**的 Actions 制品，把它们仍然占着的存储配额立刻释放出来。

## 为什么需要它（2026-08-29 实测）

GitHub 的制品存储配额打满：

    Failed to CreateArtifact: Artifact storage quota has been hit.
    Unable to upload any new artifacts. Usage is recalculated every 6-12 hours.

当时的读数：

    制品总数 95   合计 10.28 GiB   其中**已过期 10.11 GiB**
      cretas-*  63 个  10.23 GiB   (每个 Java jar 168.8 MiB)

⚠️ **不是保留期设太长** —— `java-artifact.yml` 已经是 `retention-days: 1`，
`web-dist.yml` 是 3。真因是：**过期制品要等 GitHub 每 6–12 小时重算一次才释放配额**，
而每次合并产出 169 MiB，攒得比释放快。

⛔ GitHub **没有**「过期就立刻删」这个设置。
（Settings → Actions → General 那个「Artifact and log retention」只管保留期，
而我们已经在 workflow 里写了更短的值。）⇒ 只能自己删。

## 安全性

**只删 `expired == true` 的**。那些制品 GitHub 已经判定过期、**下载不到了**，
删掉不损失任何可用的东西。

⛔ 绝不碰未过期的 —— 部署侧要按 `cretas-java-<sha>` / `cretas-web-<sha>` 取回它们。
本脚本对每一条都再检查一次 `expired` 标志才发 DELETE，
即使上游列表筛错了也删不到活的。

## 三态退出（本仓硬约束 4）

    0  跑完了（删了 N 个，或本来就没有过期的）
    1  有删除失败的
    2  这次没量到（拿不到列表 / 一个制品都没读到 —— 那多半是仪器坏了，不是「仓里没制品」）
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def api(method: str, path: str, token: str) -> tuple[int, object]:
    req = urllib.request.Request(API + path, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read().decode("utf-8")
            return r.status, (json.loads(body) if body.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw[:200]


def human(n: int) -> str:
    return "%.2f GiB" % (n / 1024 / 1024 / 1024)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--dry-run", action="store_true",
                    help="只列出会删哪些, 不真的删")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or ""
    if not token:
        print("✗ 没有 GITHUB_TOKEN —— 本次没量到")
        return 2
    if "/" not in args.repo:
        print("✗ --repo 形如 owner/name, 拿到的是 %r —— 本次没量到" % args.repo)
        return 2

    artifacts = []
    page = 1
    while True:
        st, body = api("GET", "/repos/%s/actions/artifacts?per_page=100&page=%d"
                       % (args.repo, page), token)
        if st != 200 or not isinstance(body, dict):
            print("✗ 列制品失败: HTTP %s %s —— 本次没量到" % (st, str(body)[:120]))
            return 2
        batch = body.get("artifacts") or []
        artifacts.extend(batch)
        if len(batch) < 100:
            break
        page += 1
        if page > 50:  # backstop
            break

    # 阳性对照: 一个制品都没读到, 多半是仪器坏了 —— ⛔ 不要读成「仓里干净」
    if not artifacts:
        print("✗ 一个制品都没读到 —— 扫描器可能坏了, 本次没量到")
        print("  (仓里真的一个制品都没有也会长成这样; 两者在这里分不开, 所以一律按没量到报)")
        return 2

    expired = [a for a in artifacts if a.get("expired") is True]
    live = [a for a in artifacts if a.get("expired") is not True]
    exp_bytes = sum(a.get("size_in_bytes", 0) for a in expired)
    live_bytes = sum(a.get("size_in_bytes", 0) for a in live)

    print("制品总数 %d   合计 %s" % (len(artifacts), human(exp_bytes + live_bytes)))
    print("  已过期(本次要删)  %4d 个  %s" % (len(expired), human(exp_bytes)))
    print("  未过期(⛔ 不碰)   %4d 个  %s" % (len(live), human(live_bytes)))

    if not expired:
        print("\n没有过期制品可删。")
        # ⚠️ 这不代表配额宽裕 —— 未过期的那部分照样占着。
        if live_bytes > 5 * 1024 ** 3:
            print("⚠️ 但未过期的已占 %s —— 清理救不了这一类, "
                  "要么缩短 retention-days, 要么减小制品体积。" % human(live_bytes))
        return 0

    if args.dry_run:
        print("\n[dry-run] 会删这些(只列前 10 条):")
        for a in expired[:10]:
            print("    %-40s %8.1f MiB  expires=%s"
                  % (a["name"][:40], a.get("size_in_bytes", 0) / 1024 / 1024,
                     str(a.get("expires_at"))[:10]))
        if len(expired) > 10:
            print("    ... 另有 %d 条" % (len(expired) - 10))
        return 0

    deleted = 0
    freed = 0
    failures = []
    for a in expired:
        # 🔴 发 DELETE 之前再检查一次 —— 即使上游筛错了也删不到活的制品
        if a.get("expired") is not True:
            failures.append("%s: 不是过期状态, 拒绝删除" % a.get("name"))
            continue
        st, body = api("DELETE", "/repos/%s/actions/artifacts/%s" % (args.repo, a["id"]), token)
        if st in (204, 404):  # 404 = 别人已经删了, 视为成功
            deleted += 1
            freed += a.get("size_in_bytes", 0)
        else:
            failures.append("%s (id=%s): HTTP %s %s"
                            % (a.get("name"), a.get("id"), st, str(body)[:80]))

    print("\n删除 %d / %d 个, 释放 %s" % (deleted, len(expired), human(freed)))
    if failures:
        print("✗ %d 条删除失败:" % len(failures))
        for f in failures[:10]:
            print("    " + f)
        return 1

    print("✓ 已过期制品清空")
    return 0


if __name__ == "__main__":
    sys.exit(main())
