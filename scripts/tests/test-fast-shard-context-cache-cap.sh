#!/usr/bin/env bash
# GUARDS=.github/workflows/java-gate-pr.yml
# fast-contracts-shard 的 Spring 上下文缓存上限闸 (2026-11-19):
# 托管 runner 上分片 0 反复 OOM (#1896 #1904 #1905): 每片启动 24-37 个不同上下文, 都装得进默认 32 格,
# 一个也不淘汰, 存活堆涨到 10g。修法是 argLine 里的 -Dspring.test.context.cache.maxSize=8。
#
# 判据:
#   1. fast-contracts-shard 的 mvn argLine 带 maxSize=N, 且 1 <= N <= 16 (默认 32 等于没设)
#   2. postgres-reversal-concurrency-shard 不带 maxSize (压到 4 曾让共享 PG 库被别的上下文关闭, 95 个测试报 SQL 错)
#   3. 阴性对照: 拿掉该参数 / 改成 32 / 把它塞进 PG 分片, 本闸的判断函数必须判红
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
exec python3 -I - "$ROOT_DIR" <<'PY'
import os, re, sys

root = sys.argv[1]
wf = open(os.path.join(root, ".github/workflows/java-gate-pr.yml"), encoding="utf-8").read()
KEY = "-Dspring.test.context.cache.maxSize="


def job_block(text, name):
    m = re.search(r"^  %s:\s*$" % re.escape(name), text, re.M)
    if not m:
        sys.exit("FAIL: workflow 里没有 job %s" % name)
    rest = text[m.end():]
    n = re.search(r"^  [A-Za-z0-9_-]+:\s*$", rest, re.M)
    return rest[: n.start()] if n else rest


def arg_lines(block):
    # 只看真正的 mvn 参数行 (-DargLine="..."), 注释里提到参数名不算
    return [ln for ln in block.splitlines() if ln.lstrip().startswith("-DargLine=")]


def verdict(text):
    bad = []
    fast = arg_lines(job_block(text, "fast-contracts-shard"))
    if len(fast) != 1:
        bad.append("fast-contracts-shard 应恰有 1 行 -DargLine, 实际 %d" % len(fast))
    else:
        m = re.search(re.escape(KEY) + r"(\d+)", fast[0])
        if not m:
            bad.append("fast-contracts-shard 的 argLine 缺 %sN" % KEY)
        elif not 1 <= int(m.group(1)) <= 16:
            bad.append("fast-contracts-shard 的 maxSize=%s 不在 1..16" % m.group(1))
    for ln in arg_lines(job_block(text, "postgres-reversal-concurrency-shard")):
        if KEY in ln:
            bad.append("postgres-reversal-concurrency-shard 不许带 maxSize")
    return bad


bad = verdict(wf)
print("READING: fast-contracts-shard argLine=%s" % (arg_lines(job_block(wf, "fast-contracts-shard")) or ["<none>"])[0].strip()[:200])
if bad:
    sys.exit("FAIL: " + "; ".join(bad))

# 阴性对照: 每个变异必须被判红, 且变异真的改了文本
cases = {
    "拿掉参数": wf.replace(" " + KEY + '8"', '"', 1),
    "改成 32": wf.replace(KEY + '8"', KEY + '32"', 1),
}
pg = job_block(wf, "postgres-reversal-concurrency-shard")
pg_mut = re.sub(r'(-DargLine="[^"\n]*)"', lambda m: m.group(1) + " " + KEY + '4"', pg, count=1)
cases["塞进 PG 分片"] = wf.replace(pg, pg_mut, 1)
for name, mutated in cases.items():
    if mutated == wf:
        sys.exit("FAIL: 阴性对照 [%s] 没有改动文本, 仪器坏了" % name)
    if not verdict(mutated):
        sys.exit("FAIL: 阴性对照 [%s] 没被判红" % name)
    print("READING: negative control [%s] -> red (ok)" % name)
print("OK: fast-contracts-shard 上下文缓存上限 + PG 分片不带上限")
PY
