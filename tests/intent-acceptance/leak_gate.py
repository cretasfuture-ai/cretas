"""验收集泄漏闸: 封存验收集的题不得出现在任何训练 / 开发语料里。

做什么
  读封存集指纹 (仓库里只有指纹, 没有原文), 扫描语料根目录下所有含汉字字符串的指纹, 命中即泄漏。
  本机有封存原文时 (--sealed-items) 额外做近似重复 (>=0.85 相似 / 包含) 判定; 仓库内 CI 只有指纹, 只能做整句精确命中。

退出码三态 (measurement-and-wiring 硬约束 4)
  0 = 没有泄漏, 且确实扫到了语料
  1 = 有泄漏 (打印命中的来源文件和指纹前缀, 不打印题目原文)
  2 = 没量到 (封存指纹为空 / 语料根目录一个文件也没扫到 / 参数错)

阳性/阴性对照: --self-test 在临时目录里构造「注入了一条封存题」的语料 (必须 rc=1) 和「干净语料」(必须 rc=0)
以及空目录 (必须 rc=2), 三个都符合预期才算仪器可信; tests/intent-acceptance/test_leak_gate.py 也跑同样的对照。

用法
  python -I -X utf8 tests/intent-acceptance/leak_gate.py                 # 默认指纹文件 + 默认语料根
  python -I -X utf8 tests/intent-acceptance/leak_gate.py --self-test
"""
import argparse
import json
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import corpus_guard as cg  # noqa: E402

REPO = HERE.parents[1]
DEFAULT_FP = HERE / "sealed_v3_fingerprints.jsonl"
# 训练/开发语料: 蒸馏与微调数据、各线开发集、意图目录与 Java 意图用例、本验收集的开发半边
DEFAULT_ROOTS = [
    REPO / "scripts/finetune",
    REPO / "tests/semantic-read",
    REPO / "tests/intent-acceptance/dev",
    REPO / "backend/python/ai",
    REPO / "backend/python/tests/fixtures",
    REPO / "backend/java/cretas-api/src/test/resources",
    REPO / "backend/java/cretas-api/src/main/resources/ai",
]


def load_fps(path):
    p = Path(path)
    if not p.exists():
        return set()
    out = set()
    for line in p.read_text("utf-8").splitlines():
        if line.strip():
            out.add(json.loads(line)["fp"])
    return out


def run_gate(fp_file, roots, sealed_items=None, extra_exclude=()):
    """返回 (rc, 报告 dict)。"""
    fps = load_fps(fp_file)
    report = dict(sealed_fingerprints=len(fps), files_scanned=0, corpus_strings=0, exact_hits=[], near_hits=[])
    if not fps:
        report["reason"] = "封存指纹为空: 没量到"
        return 2, report
    roots = [r for r in roots if Path(r).exists()]
    if not roots:
        report["reason"] = "语料根目录都不存在: 没量到"
        return 2, report
    seen, n_files = cg.collect(roots)
    exclude = {str(Path(e)) for e in extra_exclude}
    report["files_scanned"], report["corpus_strings"] = n_files, len(seen)
    if n_files == 0 or not seen:
        report["reason"] = "没扫到任何语料: 没量到"
        return 2, report
    for text, srcs in seen.items():
        srcs = [s for s in srcs if s not in exclude]
        if srcs and cg.fingerprint(text) in fps:
            report["exact_hits"].append(dict(fp=cg.fingerprint(text)[:12], files=srcs))
    if sealed_items:
        idx = cg.NearIndex(seen.keys())
        for line in Path(sealed_items).read_text("utf-8").splitlines():
            if not line.strip():
                continue
            q = cg.norm(json.loads(line)["input"])
            h = idx.hit(q)
            if h and h[0] != "exact":
                report["near_hits"].append(dict(kind=h[0], fp=cg.fingerprint(q)[:12], files=seen[h[1]][:2]))
    bad = report["exact_hits"] or report["near_hits"]
    return (1 if bad else 0), report


def self_test():
    """仪器自检: 阳性必须红, 阴性必须绿, 空目录必须是没量到。"""
    probe = "牛腱子冷冻库B的那批今天能出吗"  # 自检专用句, 不是任何封存题
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        fp_file = td / "fp.jsonl"
        fp_file.write_text(json.dumps(dict(fp=cg.fingerprint(probe))) + "\n", "utf-8")
        clean, dirty, empty = td / "clean", td / "dirty", td / "empty"
        for d in (clean, dirty, empty):
            d.mkdir()
        (clean / "a.jsonl").write_text(json.dumps(dict(text="今天库存还有多少")) + "\n", "utf-8")
        # 注入时换一种写法 (全角/空格/大小写/标点) 也必须命中 —— 指纹对这些归一
        (dirty / "a.jsonl").write_text(json.dumps(dict(text="牛腱子 冷冻库Ｂ的那批，今天能出吗？")) + "\n", "utf-8")
        rc_clean, _ = run_gate(fp_file, [clean])
        rc_dirty, _ = run_gate(fp_file, [dirty])
        rc_empty, _ = run_gate(fp_file, [empty])
        rc_nofp, _ = run_gate(td / "missing.jsonl", [clean])
    ok = (rc_clean, rc_dirty, rc_empty, rc_nofp) == (0, 1, 2, 2)
    print("self-test: clean=%s dirty=%s empty=%s no-fingerprints=%s (期望 0/1/2/2) -> %s" % (
        rc_clean, rc_dirty, rc_empty, rc_nofp, "OK" if ok else "FAIL"))
    return 0 if ok else 1


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--fingerprints", default=str(DEFAULT_FP))
    ap.add_argument("--root", action="append", help="语料根目录, 可多次; 不给则用默认训练/开发语料根")
    ap.add_argument("--sealed-items", help="本机封存原文 jsonl (有则额外做近似重复判定)")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args(argv)
    if a.self_test:
        return self_test()
    roots = [Path(r) for r in a.root] if a.root else DEFAULT_ROOTS
    rc, rep = run_gate(a.fingerprints, roots, a.sealed_items)
    print(json.dumps(rep, ensure_ascii=False, indent=1))
    print("leak_gate rc=%d (0 无泄漏 / 1 有泄漏 / 2 没量到)" % rc)
    return rc


if __name__ == "__main__":
    sys.exit(main())
