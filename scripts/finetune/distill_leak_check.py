#!/usr/bin/env python3
"""Anti-leak gate for distillation samples against the protected evaluation material.

Exact match + near-neighbour match (character-ratio >= .85 via the same conservative
OverlapIndex the offline experiments use, plus a character-bigram Jaccard >= .6 net) on the
normalized utterance. A hit is dropped and counted. Near-neighbour here is surface
similarity only; it is not a semantic-embedding check and says so in the report.

Usage:
  distill_leak_check.py --self-test
  distill_leak_check.py --in raw.jsonl --out clean.jsonl --dropped dropped.jsonl --report r.json
"""
import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from distill_common import read_jsonl, write_jsonl, sha256_file
from factory_accuracy_support import OverlapIndex
from flywheel_reviewed import normalized

TEXT_KEYS = {'text', 'utterance', 'message', 'user', 'input', 'content', 'question', 'prompt', 'query'}
HOME = Path.home()
DOCS = HOME / 'Documents'
# protected sets: 35 reviewed / 796 confirmation / 1637 blind / frozen holdout registry,
# the teacher probes, and every file of the opened multi-turn development dialogues.
DEFAULT_PROTECTED = [
    Path(__file__).parent / 'data/evaluation_holdout.jsonl',
    DOCS / 'Cretas意图微调-266-20261105/reviewed-35.jsonl',
    DOCS / 'Cretas意图微调-266-20261105/test.jsonl',
    DOCS / 'Cretas意图微调-266-20261105/protected.jsonl',
    DOCS / 'Cretas意图泛化-20261105/test.jsonl',
    DOCS / 'Cretas意图泛化-20261105/confirmation-paired.jsonl',
    DOCS / 'Cretas意图泛化-20261105/protected-disagreements.jsonl',
    DOCS / 'Cretas意图泛化-20261105/negative-confirmation.jsonl',
    DOCS / 'Cretas意图泛化-20261105/original35-new266.jsonl',
    DOCS / 'Cretas意图准确率-20261105/test.jsonl',
    DOCS / 'Cretas意图准确率-20261105/blind-paired.jsonl',
    DOCS / 'Cretas意图准确率-20261105/protected-disagreements.jsonl',
    DOCS / 'Cretas意图教师复核-20261105/probes.jsonl',
    DOCS / 'Cretas意图架构只读审计-20261105',          # directory: scanned recursively
]


def _cjk(s):
    return any('一' <= c <= '鿿' for c in s)


def _walk(obj, key, out):
    if isinstance(obj, dict):
        for k, v in obj.items():
            _walk(v, k, out)
    elif isinstance(obj, list):
        for v in obj:
            _walk(v, key, out)
    elif isinstance(obj, str) and key in TEXT_KEYS and 2 <= len(obj) <= 300 and _cjk(obj):
        out.append(obj)


def extract_texts(path):
    path = Path(path)
    files = [p for p in path.rglob('*') if p.suffix in ('.json', '.jsonl', '.saved')] if path.is_dir() else [path]
    texts = []
    for f in files:
        try:
            raw = f.read_text(encoding='utf-8-sig')
        except (OSError, UnicodeDecodeError):
            continue
        try:
            _walk(json.loads(raw), None, texts)
        except ValueError:
            for line in raw.splitlines():
                try:
                    _walk(json.loads(line), None, texts)
                except ValueError:
                    pass
    return texts


def _bigrams(t):
    return {t[i:i + 2] for i in range(len(t) - 1)} or {t}


class ProtectedIndex:
    def __init__(self, texts, manifest=None):
        uniq = sorted({normalized(t) for t in texts if normalized(t)})
        self.texts = uniq
        self.exact = set(uniq)
        self.manifest = manifest or []
        self.overlap = OverlapIndex([{'text': t} for t in uniq])
        self.bigrams = [_bigrams(t) for t in uniq]
        post = defaultdict(list)
        for i, b in enumerate(self.bigrams):
            for g in b:
                post[g].append(i)
        self.post = post

    @classmethod
    def from_sources(cls, paths):
        texts, manifest = [], []
        for p in paths:
            p = Path(p)
            if not p.exists():
                raise FileNotFoundError('Protected source missing (a silently smaller protected set is a leak): %s' % p)
            t = extract_texts(p)
            manifest.append(dict(path=str(p), texts=len(t), sha256=sha256_file(p) if p.is_file() else 'dir'))
            texts.extend(t)
        return cls(texts, manifest)

    def check(self, text):
        """Return None if clean, else 'exact' | 'near_char' | 'near_bigram'."""
        n = normalized(text)
        if not n:
            raise ValueError('Empty utterance')
        if n in self.exact:
            return 'exact'
        if self.overlap.overlaps({'text': text}):
            return 'near_char'
        b = _bigrams(n)
        cand = defaultdict(int)
        for g in b:
            for i in self.post.get(g, ()):
                cand[i] += 1
        for i, inter in cand.items():
            if inter / (len(b) + len(self.bigrams[i]) - inter) >= .6:
                return 'near_bigram'
        return None


def filter_samples(rows, index):
    clean, dropped = [], []
    reasons = defaultdict(int)
    for r in rows:
        why = index.check(r['utterance'])
        if why:
            dropped.append(dict(r, leak_reason=why))
            reasons[why] += 1
        else:
            clean.append(r)
    return clean, dropped, dict(reasons)


def self_test(index=None):
    """Prove the gate bites: an exact copy and a one-character edit of a protected text must
    be dropped, and an unrelated utterance must pass. Returns the three observations."""
    index = index or ProtectedIndex(['查一下这周的销售数据', '把今天的出库单全部确认发货'])
    probes = [('exact', '查一下这周的销售数据'), ('near', '查一下这周的销售数据吧'),
              ('clean', '冷库三号间的温度记录发我一下')]
    seen = {name: index.check(text) for name, text in probes}
    assert seen['exact'] == 'exact', seen
    assert seen['near'] in ('near_char', 'near_bigram'), seen
    assert seen['clean'] is None, seen
    return seen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--self-test', action='store_true')
    ap.add_argument('--in', dest='src')
    ap.add_argument('--out')
    ap.add_argument('--dropped')
    ap.add_argument('--report')
    ap.add_argument('--protected', nargs='*', default=None)
    a = ap.parse_args()
    if a.self_test:
        print('SELF_TEST', json.dumps(self_test(), ensure_ascii=False))
        return
    index = ProtectedIndex.from_sources(a.protected or DEFAULT_PROTECTED)
    rows = read_jsonl(a.src)
    clean, dropped, reasons = filter_samples(rows, index)
    write_jsonl(a.out, clean)
    write_jsonl(a.dropped, dropped)
    report = dict(checked=len(rows), clean=len(clean), dropped=len(dropped), reasons=reasons,
                  protected_texts=len(index.texts), protected_sources=index.manifest,
                  method='exact + char-ratio>=.85 + bigram-jaccard>=.6; surface similarity, not embeddings',
                  self_test=self_test(index))
    Path(a.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('LEAK_CHECK', json.dumps({k: report[k] for k in ('checked', 'clean', 'dropped', 'reasons')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
