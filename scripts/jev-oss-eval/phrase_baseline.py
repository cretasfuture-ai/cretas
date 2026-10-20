"""工厂子串短语层摸底: 把 IntentKnowledgeBase.doMatchPhrase 原样移植, 在考卷的工厂题上跑。

输入: Java 源 (git show origin/main:.../IntentKnowledgeBase.java 落到本地的副本)
      + IntentRecognitionPipelineServiceImpl.filterFillerWordsForPhrase 的副本
输出: 命中率 / 命中正确率 / 子串误判示例。intent -> tool 用 intents_repo.json 的 tool_name 映射。

⚠️ 这只量「短语层单独会怎么判」。生产上它在 Python 匹配器之后才执行(按 2026-05 配置), 不代表线上路由结果。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from common import HERE, load_testset

IKB = Path(sys.argv[1] if len(sys.argv) > 1 else "D:/Temp/IKB.java")
IRP = Path(sys.argv[2] if len(sys.argv) > 2 else "D:/Temp/IRP.java")

PUT = r'\.put\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"([A-Z0-9_]+)"\s*\)'


def load_maps(src: str):
    def puts(name):
        out = {}
        for k, v in re.findall(name + PUT, src):
            out[k] = v  # LinkedHashMap: 后 put 覆盖前 put
        return out, len(re.findall(name + PUT, src))

    fac, n_fac = puts("phraseToIntentMapping")
    com, n_com = puts("commonPhraseMapping")
    m = re.search(r"FOOD_PRODUCT_NAMES\s*=\s*Set\.of\((.*?)\);", src, re.S)
    foods = re.findall(r'"([^"]+)"', m.group(1)) if m else []
    return fac, com, foods, n_fac, n_com


def filler_filter_source(irp: str) -> str:
    i = irp.find("filterFillerWordsForPhrase(String")
    return irp[i : i + 2500] if i >= 0 else ""


STRIP_QTY = re.compile(r"\d+(\.\d+)?\s*(kg|g|公斤|斤|吨|升|ml|l|个|箱|袋|包|瓶|件|台|号|%)")
STRIP_BATCH = re.compile(r"[a-zA-Z]{1,3}[-]?\d{3,}(?:[-]\d+)*")


def do_match_phrase(inp: str, phrase_map: dict, foods: list[str]):
    """IntentKnowledgeBase.doMatchPhrase 的逐行移植。返回 (intent, phrase, layer) 或 None。"""
    if not inp or not inp.strip() or not phrase_map:
        return None
    norm = inp.strip().lower()
    stripped = re.sub(r"\s+", "", STRIP_BATCH.sub("", STRIP_QTY.sub("", norm)))
    deep = stripped
    for w in foods:
        deep = deep.replace(w, "")
    deep = re.sub(r"\s+", "", deep)
    n = len(norm)
    for phrase_raw, intent in sorted(phrase_map.items(), key=lambda kv: -len(kv[0])):  # Java: 稳定排序按长度倒序
        phrase = phrase_raw.lower()
        pl = len(phrase)
        in_norm = phrase in norm
        in_strip = (not in_norm) and phrase in stripped
        in_deep = (not in_norm) and (not in_strip) and deep != stripped and phrase in deep
        if in_norm or in_strip or in_deep:
            eff = len(deep) if in_deep else len(stripped) if in_strip else n
            exact = pl == eff
            long_p = pl >= 4
            short_in = eff <= 6 and pl >= 2
            cov = pl / eff if eff else 0
            if exact or long_p or cov >= 0.4 or short_in:
                return intent, phrase_raw, ("exact" if exact else "long" if long_p else "short" if short_in else "cov")
    return None


def main() -> int:
    src = IKB.read_text(encoding="utf-8")
    fac, com, foods, n_fac, n_com = load_maps(src)
    print(f"phrase puts factory={n_fac} distinct={len(fac)} | common puts={n_com} distinct={len(com)} | food names={len(foods)}")
    if len(fac) < 1000 or not foods:
        print("INSTRUMENT DEAD: 短语表或食材名单没抽出来, 读数作废")
        return 2
    filler_src = filler_filter_source(IRP.read_text(encoding="utf-8"))
    print("filterFillerWordsForPhrase found:", bool(filler_src), "(本移植未实现填充词过滤, 结果是它的上界近似)")

    intents = json.loads((HERE / "intents_repo.json").read_text(encoding="utf-8"))
    rows = [r for r in load_testset() if r["ctx"] == "factory"]
    hits, correct, wrong, unmapped = [], [], [], []
    for r in rows:
        m = do_match_phrase(r["q"], fac, foods) or do_match_phrase(r["q"], com, foods)
        if not m:
            continue
        intent, phrase, layer = m
        tool = (intents.get(intent) or {}).get("tool_name")
        rec = {"id": r["id"], "route": r["route"], "q": r["q"], "phrase": phrase, "layer": layer,
               "intent": intent, "tool": tool, "gold": r["gold"]}
        hits.append(rec)
        if not tool:
            unmapped.append(rec)
        elif tool in r["gold"]:
            correct.append(rec)
        else:
            wrong.append(rec)
    n = len(rows)
    print(f"factory rows {n} | 命中 {len(hits)} ({len(hits) / n:.1%}) | 命中且工具正确 {len(correct)} | 命中但工具错 {len(wrong)} | 命中但意图无 tool_name 无法判 {len(unmapped)}")
    by_route = {}
    for h in hits:
        by_route.setdefault(h["route"], [0, 0])
        by_route[h["route"]][0] += 1
        by_route[h["route"]][1] += int(h in correct)
    print("按类别 (命中/其中正确):", by_route)
    print("--- 命中但工具错 (全部) ---")
    for w in wrong:
        print(f"  {w['id']} [{w['route']}] {w['q']} | 短语「{w['phrase']}」({w['layer']}) -> {w['intent']} -> {w['tool']} | 金标 {w['gold'][:2]}")
    print("--- 命中但意图无 tool_name ---")
    for u in unmapped:
        print(f"  {u['id']} {u['q']} | 短语「{u['phrase']}」 -> {u['intent']}")
    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results" / "phrase_baseline.json").write_text(json.dumps({"hits": hits}, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
