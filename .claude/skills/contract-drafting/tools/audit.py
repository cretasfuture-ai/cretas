# -*- coding: utf-8 -*-
"""对外合同文本审计。用法: python audit.py <md 或 docx 文件...>   （docx 会先用 pandoc 转纯文本）
只报告不改。退出码: 0 全部干净 / 1 有命中需人看 / 2 没量到（文件读不到、pandoc 缺失）。
命中不等于违规——「AI建议」「按比例折算」「本次签署文本取代此前草案」是合法用法，逐条看上下文。"""
import io, os, re, subprocess, sys, tempfile

META = ["本稿", "本说明", "只签", "另有文本", "【", "】", "◆", "待定", "待议", "待填", "待明确", "待确认", "供审阅", "审阅稿", "草稿",
        "版本/日期", "版本标识", "不表述为", "本句不构成", "亦不因该引用", "以完全相同的文字", "起草", "备注", "注：", "鉴于", "一般条款", "以资"]
JARGON = ["释放权", "释放金", "现金释放", "锁款", "清关", "服务积分", "容量", "统一责任结构", "跨文件", "需求池", "待评估池", "路线图", "迭代",
          "打通", "闭环", "追平", "应急支持包", "履行边界", "时间边界", "赋能", "抓手", "落地", "对齐", "复盘", "颗粒度", "兜底", "闭合", "以下简称", "以下统称"]
ACRONYM = ["T0", "P0", "P1", "P2", "SLA", "RTO", "RPO", "人日", "Call", "Put", "Logo", "API", "Wi-Fi", "MFA", "VPN", "App", "SPV", "LPR",
           "Bug", "Critical", "KPI"]
SYMBOLS = ["→", "≤", "≥", " · ", "「", "」", "——", "…", "¥"]
INTERNAL = ["号文件", "Q-", "拍板", "让步", "底线", "开局", "筹码"]
CJK = "[一-鿿　-〿＀-￯]"
RE_SP = re.compile("(?:(?<=" + CJK + ") +(?=[A-Za-z0-9%$]))|(?:(?<=[A-Za-z0-9%$]) +(?=" + CJK + "))")
LABEL = re.compile(r"^( {0,3}(?:\d+(?:\.\d+)+[a-z]?|R\.\d+(?:\.\d+)*|\d+\.|[（(][a-z0-9]{1,4}[)）]|[-*+]|>|#{1,6}|\|) )")
KEEP_BOLD = re.compile(r"^( {0,3}(?:\d+(?:\.\d+)+[a-z]?|R\.\d+(?:\.\d+)*|\d+\.|[（(][a-z0-9]{1,4}[)）]|[-*+]|>) )?\*\*[^*]+\*\*(?=[：。（]|$)")
VARS = re.compile(r"(?<![A-Za-z])([A-HJ-Z]|P1|C2|T3|ΔC)(?![A-Za-z0-9])")

def load(path):
    if path.lower().endswith(".docx"):
        tmp = tempfile.mktemp(suffix=".txt")
        r = subprocess.run(["pandoc", path, "-t", "plain", "--wrap=none", "-o", tmp], capture_output=True)
        if r.returncode != 0:
            print("pandoc 失败:", r.stderr.decode(errors="ignore")[:200]); sys.exit(2)
        s = io.open(tmp, encoding="utf-8").read()
    else:
        s = io.open(path, encoding="utf-8").read()
    s = s.replace("\r\n", "\n")
    s = re.sub(r"^---\n.*?\n---\n", "", s, count=1, flags=re.S)  # frontmatter
    return s

def audit(path):
    s = load(path); lines = s.split("\n"); hits = 0
    print("=" * 70); print(path, "|", len(s), "字符")
    for label, words in (("解释性/占位", META), ("直译/黑话", JARGON), ("缩写", ACRONYM), ("符号", SYMBOLS), ("内部痕迹", INTERNAL)):
        found = {}
        for w in words:
            ls = [l for l in lines if w in l]
            if ls: found[w] = ls
        if found:
            hits += len(found); print("  [%s]" % label)
            for w, ls in found.items(): print("    %-10s ×%d  %s" % (w, len(ls), ls[0][:100]))
    odd = []; hw = []; dash = []; mid = []
    for l in lines:
        m = LABEL.match(l); rest = l[m.end():] if m else l
        if RE_SP.search(rest): odd.append(l[:90])
        if re.search(r"\(([a-z]{1,4}|\d{1,2})\)", l): hw.append(l[:90])
        if re.search(r"\d–\d", l): dash.append(l[:90])
        if "**" in l and not l.startswith("#") and not KEEP_BOLD.match(l): mid.append(l[:90])
    for label, ls in (("汉字-数字空格(条款号外)", odd), ("半角(a)/(1)", hw), ("数字间–", dash), ("句中加粗", mid)):
        print("  %s: %d" % (label, len(ls)), ls[:2] if ls else ""); hits += bool(ls)
    sents = [t.strip() for l in lines if l.strip() and not l.startswith(("|", "#")) for t in re.split(r"(?<=[。；])", l) if len(t.strip()) >= 8]
    if sents:
        long_ = [t for t in sents if len(t) >= 120]; neg = [t for t in sents if t.count("不") >= 4]
        print("  句子 %d，平均 %.0f 字，≥120 字 %d，含≥4 个「不」 %d" % (len(sents), sum(map(len, sents)) / len(sents), len(long_), len(neg)))
    vs = {}
    for l in lines:
        if l.startswith("|"): continue
        for m in VARS.finditer(l):
            ctx = l[max(0, m.start() - 6): m.end() + 6]
            if re.search(r"[A-Za-z]{2,}", ctx.replace(m.group(0), "")): continue
            vs[m.group(0)] = vs.get(m.group(0), 0) + 1
    print("  单字母变量:", vs or "无"); hits += bool(vs)
    clauses = set(re.findall(r"^ {0,3}(\d+\.\d+(?:\.\d+)?)[a-z]? ", s, re.M))
    refs = set(re.findall(r"第(\d+\.\d+(?:\.\d+)?)条", s))
    missing = sorted(r for r in refs if r not in clauses)
    heads = set(re.findall(r"^#+ *(附件[一二三四五六七八九十]+)", s, re.M)) or set(re.findall(r"^(附件[一二三四五六七八九十]+)　", s, re.M))
    aref = set(re.findall(r"(附件[一二三四五六七八九十]+)", s))
    print("  条款引用缺失(可能指向另一文件):", missing or "无", "| 附件引用缺失:", sorted(aref - heads) if heads and (aref - heads) else "无")
    nums = sorted(set(re.findall(r"\d{2,3},\d{3}|\d+万", s)), key=lambda x: (len(x), x))
    print("  金额出现:", nums[:20])
    return hits

if __name__ == "__main__":
    if len(sys.argv) < 2: print(__doc__); sys.exit(2)
    total = 0
    for p in sys.argv[1:]:
        if not os.path.exists(p): print("文件不存在:", p); sys.exit(2)
        total += audit(p)
    print("\n命中项 %d（逐条看上下文，合法用法不算违规）" % total)
    sys.exit(1 if total else 0)
