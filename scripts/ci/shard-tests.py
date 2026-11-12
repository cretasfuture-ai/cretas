#!/usr/bin/env python3
"""Deterministic test-class sharding for java-gate-pr.yml.

Usage: shard-tests.py --list FILE [--shard I --shards N] [--test-root DIR]
  --list   one surefire class pattern per line ('#' comments; '*' globs, explicit names allowed)
  no --shard: print every matched class (the full set); with --shard: only those where
  md5(name) % N == I. Output is a comma-separated list, ready for -Dtest=.
A pattern that matches no test file is an error (explicit names: a deleted/renamed class must not
silently drop out of the gate).
"""
import argparse
import fnmatch
import hashlib
import os
import sys

DEFAULT_ROOT = "backend/java/cretas-api/src/test/java"


def read_patterns(path):
    with open(path, encoding="utf-8") as f:
        return [ln.strip() for ln in f if ln.strip() and not ln.lstrip().startswith("#")]


def all_classes(root):
    names = set()
    for _, _, files in os.walk(root):
        for f in files:
            if f.endswith(".java"):
                names.add(f[:-5])
    return names


def matched(patterns, classes):
    out, empty = set(), []
    for p in patterns:
        hit = {c for c in classes if fnmatch.fnmatchcase(c, p)}
        if not hit:
            empty.append(p)
        out |= hit
    return sorted(out), empty


def shard_of(name, n):
    return int(hashlib.md5(name.encode()).hexdigest()[:8], 16) % n


WEIGHTS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "java-gate-shard-weights.txt")
DEFAULT_WEIGHT = 1.0


def read_weights(path):
    """'ClassName seconds' per line; '#' comments. Missing file -> None (fall back to md5 % N)."""
    if not os.path.isfile(path):
        return None
    out = {}
    with open(path, encoding="utf-8") as f:
        for ln in f:
            ln = ln.strip()
            if not ln or ln.startswith("#"):
                continue
            name, secs = ln.split()
            out[name] = float(secs)
    return out


def assign_balanced(names, n, weights):
    """Deterministic LPT: heaviest first (ties by name), each to the currently lightest shard
    (ties by lowest index). Every class lands in exactly one shard."""
    loads = [0.0] * n
    owner = {}
    for name in sorted(names, key=lambda x: (-weights.get(x, DEFAULT_WEIGHT), x)):
        i = min(range(n), key=lambda k: (loads[k], k))
        owner[name] = i
        loads[i] += weights.get(name, DEFAULT_WEIGHT)
    return owner


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", required=True)
    ap.add_argument("--shard", type=int)
    ap.add_argument("--shards", type=int)
    ap.add_argument("--test-root", default=DEFAULT_ROOT)
    a = ap.parse_args()
    pats = read_patterns(a.list)
    if not pats:
        sys.exit("ERROR: no patterns in %s" % a.list)
    classes = all_classes(a.test_root)
    if not classes:
        sys.exit("ERROR: no test classes under %s" % a.test_root)
    names, empty = matched(pats, classes)
    if empty:
        sys.exit("ERROR: patterns matching no test class: %s" % ", ".join(empty))
    if a.shard is not None:
        if not a.shards or not 0 <= a.shard < a.shards:
            sys.exit("ERROR: need --shards N and 0 <= --shard < N")
        weights = read_weights(WEIGHTS_FILE)
        if weights is None:
            names = [n for n in names if shard_of(n, a.shards) == a.shard]
        else:
            owner = assign_balanced(names, a.shards, weights)
            names = [n for n in names if owner[n] == a.shard]
        if not names:
            sys.exit("ERROR: shard %d/%d is empty" % (a.shard, a.shards))
    print(",".join(names))


if __name__ == "__main__":
    main()
