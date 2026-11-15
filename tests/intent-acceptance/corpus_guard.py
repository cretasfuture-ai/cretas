"""验收集与训练/开发语料的隔离: 指纹、语料字符串收集、近似重复判定 (出题去重和泄漏闸共用, 同一份算法)。

指纹 = sha256(NFKC -> 小写 -> 去掉空白与非字母数字字符)，与 v1/v2 验收集同一算法 (tests/semantic-read/HOLDOUT*.md)。
语料字符串: 目录下 .json/.jsonl/.csv/.txt 里所有含汉字、规范化后 2..80 字的字符串 (宁多勿漏: 目录名、标签只会多报，不会漏报)。
近似重复: 规范化文本 SequenceMatcher >= NEAR, 或较短一句 (>= 6 字) 被另一句包含。
"""
import collections
import difflib
import hashlib
import json
import re
import unicodedata
from pathlib import Path

NEAR = 0.85
CJK = re.compile(r"[一-鿿]")
SKIP_DIRS = {"node_modules", ".git", "venv", "venv-current", "__pycache__", "target", "dist"}


def norm(s):
    s = unicodedata.normalize("NFKC", s).lower()
    return "".join(ch for ch in s if ch.isalnum())


def fingerprint(s):
    return hashlib.sha256(norm(s).encode("utf-8")).hexdigest()


def _strings(obj):
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from _strings(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from _strings(v)


def iter_files(roots, max_bytes=200_000_000):
    for root in roots:
        root = Path(root)
        if root.is_file():
            yield root
            continue
        if not root.exists():
            continue
        for p in root.rglob("*"):
            if SKIP_DIRS & set(p.parts):
                continue
            if p.suffix in (".json", ".jsonl", ".csv", ".txt") and p.is_file() and p.stat().st_size <= max_bytes:
                yield p


def file_strings(p):
    try:
        text = p.read_text("utf-8-sig", errors="replace")
    except OSError:
        return []
    vals = []
    if p.suffix == ".jsonl":
        for line in text.splitlines():
            try:
                vals.extend(_strings(json.loads(line)))
            except ValueError:
                vals.append(line)
    elif p.suffix == ".json":
        try:
            vals.extend(_strings(json.loads(text)))
        except ValueError:
            vals.append(text)
    else:
        vals.extend(text.splitlines())
        for line in text.splitlines():
            vals.extend(c.strip() for c in re.split(r"[,\t]", line))
    return vals


def collect(roots):
    """返回 ({规范化文本: [来源文件, ...]}, 扫描文件数)。只收含汉字、2..80 字的字符串。"""
    seen, n_files = collections.defaultdict(list), 0
    for p in iter_files(roots):
        n_files += 1
        for s in file_strings(p):
            if CJK.search(s):
                n = norm(s)
                if 2 <= len(n) <= 80 and len(seen[n]) < 3:
                    seen[n].append(str(p))
    return seen, n_files


def _bigrams(t):
    return {t[i:i + 2] for i in range(len(t) - 1)}


class NearIndex:
    """语料规范化文本的二元组倒排索引, 用来给近似重复判定做预筛。"""

    def __init__(self, texts):
        self.texts = list(texts)
        self.inv = collections.defaultdict(list)
        for i, t in enumerate(self.texts):
            for g in _bigrams(t):
                self.inv[g].append(i)

    def hit(self, q):
        """q 为规范化文本; 返回 (类型, 命中的语料文本) 或 None。"""
        qb = _bigrams(q)
        if not qb:
            return None
        cnt = collections.Counter()
        for g in qb:
            for i in self.inv.get(g, ()):
                cnt[i] += 1
        for i, c in cnt.most_common(50):
            t = self.texts[i]
            if t == q:
                return ("exact", t)
            if c / max(len(qb), 1) < 0.4 and min(len(t), len(q)) > 8:
                continue
            if difflib.SequenceMatcher(None, q, t).ratio() >= NEAR:
                return ("near", t)
            short, long_ = (q, t) if len(q) <= len(t) else (t, q)
            if len(short) >= 6 and short in long_:
                return ("contain", t)
        return None
