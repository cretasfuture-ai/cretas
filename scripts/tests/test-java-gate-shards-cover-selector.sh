#!/usr/bin/env bash
# GUARDS=.github/workflows/java-gate-pr.yml,scripts/ci/shard-tests.py,scripts/ci/java-gate-fast-patterns.txt,scripts/ci/java-gate-pg-tests.txt,backend/java/cretas-api/src/test/java/**
# 分片覆盖闸 (2026-11-10): fast-contracts / postgres-reversal-concurrency 拆成 4 片并行后,
# 所有分片的并集必须 == 原选择器匹配到的集合, 分片之间不重叠。丢类或丢片必须变红。
#
# 判据 (独立于 shard-tests.py 的匹配实现: 这里自己用 fnmatch 走一遍测试源码树):
#   1. workflow 里每个 *-shard job 的 matrix.shard 恰好是 0..N-1, 且与 --shards N 的 N 一致 (漏一片 => 红)
#   2. 对每片真的跑 shard-tests.py, 并集 == 独立算出的全集, 两两不相交, 每片非空
#   3. 阴性对照: 拿掉一个类 / 拿掉一片, 本闸的比较函数必须判红 (证明它能红)
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
exec python3 -I - "$ROOT_DIR" <<'PY'
import fnmatch, os, re, subprocess, sys

root = sys.argv[1]
wf = open(os.path.join(root, ".github/workflows/java-gate-pr.yml"), encoding="utf-8").read()
test_root = os.path.join(root, "backend/java/cretas-api/src/test/java")
JOBS = {
    "fast-contracts-shard": "scripts/ci/java-gate-fast-patterns.txt",
    "postgres-reversal-concurrency-shard": "scripts/ci/java-gate-pg-tests.txt",
}


def job_block(name):
    m = re.search(r"^  %s:\s*$" % re.escape(name), wf, re.M)
    if not m:
        sys.exit("FAIL: workflow 里没有 job %s" % name)
    rest = wf[m.end():]
    n = re.search(r"^  [A-Za-z0-9_-]+:\s*$", rest, re.M)
    return rest[: n.start()] if n else rest


classes = set()
for _, _, files in os.walk(test_root):
    classes |= {f[:-5] for f in files if f.endswith(".java")}
if len(classes) < 500:
    sys.exit("FAIL: 只扫到 %d 个测试类, 仪器坏了" % len(classes))


def independent_full(listfile):
    pats = [l.strip() for l in open(os.path.join(root, listfile), encoding="utf-8")
            if l.strip() and not l.lstrip().startswith("#")]
    full = set()
    for p in pats:
        hit = {c for c in classes if fnmatch.fnmatchcase(c, p)}
        if not hit:
            sys.exit("FAIL: 清单模式 %s 没有匹配任何测试类" % p)
        full |= hit
    return full


def verdict(full, shards):
    """红的原因列表; 空 = 全覆盖且不重叠。"""
    bad, seen = [], set()
    union = set()
    for i, s in enumerate(shards):
        if not s:
            bad.append("分片 %d 为空" % i)
        dup = union & s
        if dup:
            bad.append("分片 %d 与前面重叠: %s" % (i, sorted(dup)[:3]))
        union |= s
    if union != full:
        bad.append("并集 != 全集: 漏 %s 多 %s" % (sorted(full - union)[:5], sorted(union - full)[:5]))
    return bad


problems = []
for job, listfile in JOBS.items():
    blk = job_block(job)
    m = re.search(r"shard:\s*\[([^\]]*)\]", blk)
    t = re.search(r"--shards\s+(\d+)", blk)
    if not m or not t:
        sys.exit("FAIL: %s 缺 matrix.shard 或 --shards" % job)
    declared = [int(x) for x in m.group(1).split(",")]
    total = int(t.group(1))
    print("READING: %s matrix.shard=%s --shards=%d list=%s" % (job, declared, total, listfile))
    if declared != list(range(total)):
        problems.append("%s: matrix.shard %s != 0..%d (漏片)" % (job, declared, total - 1))
    if listfile not in blk:
        problems.append("%s: 没引用 %s" % (job, listfile))
    full = independent_full(listfile)
    shards = []
    for i in range(total):
        out = subprocess.run([sys.executable, os.path.join(root, "scripts/ci/shard-tests.py"),
                              "--list", os.path.join(root, listfile), "--shard", str(i),
                              "--shards", str(total), "--test-root", test_root],
                             capture_output=True, text=True)
        if out.returncode != 0:
            problems.append("%s shard %d: %s" % (job, i, out.stderr.strip()))
            shards.append(set())
        else:
            shards.append(set(out.stdout.strip().split(",")))
    print("READING: %s full=%d per_shard=%s" % (job, len(full), [len(s) for s in shards]))
    bad = verdict(full, shards)
    problems += ["%s: %s" % (job, b) for b in bad]
    # 阴性对照: 必须能红
    victim = sorted(shards[0])[0]
    dropped_class = [shards[0] - {victim}] + shards[1:]
    if not verdict(full, dropped_class):
        problems.append("%s: 阴性对照失效: 漏掉类 %s 没被判红" % (job, victim))
    if not verdict(full, shards[:-1]):
        problems.append("%s: 阴性对照失效: 漏掉最后一片没被判红" % job)
    print("  ok - %s 阴性对照: 漏一个类 / 漏一片都会被判红" % job)

if problems:
    for p in problems:
        print("FAIL: " + p)
    sys.exit(1)
print("PASS: 分片并集 == 选择器全集, 无重叠")
PY
