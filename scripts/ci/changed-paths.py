#!/usr/bin/env python3
"""判断这次 PR 有没有改到某道闸的触发路径, 往 $GITHUB_OUTPUT 写 run=true|false。

为什么有它 (2026-11-12, 合并队列第 1 步):
  要把检查设成「必需」并开合并队列, 每个检查必须在 pull_request 上【总是】报告结果。
  workflow 级 `on.pull_request.paths` 过滤掉的 run 根本不会出现 ⇒ 必需检查永远挂起。
  ⇒ pull_request 不再带 paths, 改由每个 workflow 的 `changes` 作业调用本脚本判定;
  原作业 `needs: changes` 并按 run 决定跑不跑 (被 if 跳过的作业对必需检查算通过)。

触发路径清单: scripts/ci/trigger-paths/<workflow 名>.txt, 一行一个 glob, `#` 起注释。
glob 语义对齐 GitHub Actions 的 paths 过滤:
  `**`  跨目录匹配任意字符 (`a/**` 匹配 a 下全部文件; `**/x` 匹配任意深度的 x, 含根)
  `*`   不跨 `/`
  `?`   单个非 `/` 字符
  `!p`  取反: 按顺序求值, 最后一条命中的模式决定结果 (与 GitHub 相同)

判定 (⛔ 不许因为没量到而跳过 —— 宁可多跑):
  事件不是 pull_request            ⇒ run=true (merge_group 队列跑全量; push/schedule/dispatch 行为不变)
  base/head sha 缺失、git 出错、diff 为空 ⇒ run=true 并打 ::warning::
  有任一变更文件命中                ⇒ run=true
  全部不命中                        ⇒ run=false

退出码: 0 = 做出了判定 (含兜底的 true); 2 = 用法错误 / 清单读不到或为空 (仪器坏了, 让作业红,
下游 `needs.changes.outputs.run != 'false'` 因输出为空仍然会跑)。
"""
import argparse
import os
import re
import subprocess
import sys


def glob_to_regex(pattern):
    """GitHub Actions paths glob → 编译好的正则 (整串匹配)。"""
    i, n, out = 0, len(pattern), []
    while i < n:
        c = pattern[i]
        if c == "*":
            if i + 1 < n and pattern[i + 1] == "*":
                # `**`
                j = i + 2
                if j < n and pattern[j] == "/":
                    # `**/` : 零个或多个目录
                    out.append("(?:.*/)?")
                    i = j + 1
                else:
                    out.append(".*")
                    i = j
                continue
            out.append("[^/]*")
        elif c == "?":
            out.append("[^/]")
        else:
            out.append(re.escape(c))
        i += 1
    return re.compile("^" + "".join(out) + "$")


def read_patterns(path):
    """读清单: 去掉整行注释与行尾 ` # 注释`, 保留顺序。"""
    pats = []
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            line = re.sub(r"\s+#.*$", "", line).strip()
            if line:
                pats.append(line)
    return pats


def matches(path, compiled):
    """按顺序求值, 最后一条命中的模式说了算 (取反模式命中 ⇒ 排除)。"""
    hit = False
    for negate, rx in compiled:
        if rx.match(path):
            hit = not negate
    return hit


def compile_patterns(pats):
    out = []
    for p in pats:
        negate = p.startswith("!")
        out.append((negate, glob_to_regex(p[1:] if negate else p)))
    return out


def changed_files(base, head, cwd=None):
    """git diff --name-only base...head; 失败返回 (None, 原因)。"""
    for sha in (base, head):
        r = subprocess.run(["git", "cat-file", "-e", sha + "^{commit}"], cwd=cwd,
                           capture_output=True, text=True)
        if r.returncode != 0:
            return None, "本地没有提交 %s (rc=%d)" % (sha, r.returncode)
    # --no-renames: 改名时旧路径与新路径都列出 (宁可多命中)。
    r = subprocess.run(["git", "diff", "--name-only", "--no-renames", "%s...%s" % (base, head)],
                       cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        return None, "git diff rc=%d: %s" % (r.returncode, r.stderr.strip()[:300])
    return [l.strip() for l in r.stdout.splitlines() if l.strip()], None


def decide(event, base, head, pats, cwd=None):
    """返回 (run, changed_n, matched_list, warning)。"""
    if event != "pull_request":
        return True, None, [], None
    if not base or not head:
        return True, None, [], "base/head sha 缺失 (base=%r head=%r), 按 run=true 处理" % (base, head)
    files, err = changed_files(base, head, cwd)
    if files is None:
        return True, None, [], "取不到 diff (%s), 按 run=true 处理" % err
    if not files:
        return True, 0, [], "diff 为空 (0 个变更文件), 读数可疑, 按 run=true 处理"
    compiled = compile_patterns(pats)
    hit = [f for f in files if matches(f, compiled)]
    return bool(hit), len(files), hit, None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--patterns", required=True, help="触发路径清单文件")
    ap.add_argument("--event", default=os.environ.get("EVENT_NAME") or os.environ.get("GITHUB_EVENT_NAME", ""))
    ap.add_argument("--base", default=os.environ.get("BASE_SHA", ""))
    ap.add_argument("--head", default=os.environ.get("HEAD_SHA", ""))
    ap.add_argument("--output", default=os.environ.get("GITHUB_OUTPUT", ""),
                    help="写 run=... 的文件 (默认 $GITHUB_OUTPUT; 为空则只打印)")
    a = ap.parse_args(argv)

    try:
        pats = read_patterns(a.patterns)
    except OSError as e:
        print("::error::读不到触发路径清单 %s: %s" % (a.patterns, e))
        return 2
    if not [p for p in pats if not p.startswith("!")]:
        print("::error::触发路径清单 %s 没有任何正向模式 —— 仪器坏了, ⛔ 不当成「不用跑」" % a.patterns)
        return 2

    run, n, hit, warn = decide(a.event, a.base, a.head, pats)
    if warn:
        print("::warning::changed-paths: " + warn)
    value = "true" if run else "false"
    print("READING: event=%s base=%s head=%s changed_files=%s matched=%d run=%s patterns=%s(%d)"
          % (a.event or "<empty>", a.base or "<empty>", a.head or "<empty>",
             "n/a" if n is None else n, len(hit), value, a.patterns, len(pats)))
    for f in hit[:20]:
        print("  matched: " + f)
    if len(hit) > 20:
        print("  ... 另有 %d 个命中" % (len(hit) - 20))
    if a.output:
        with open(a.output, "a", encoding="utf-8", newline="") as fh:
            fh.write("run=%s\n" % value)
    return 0


if __name__ == "__main__":
    sys.exit(main())
