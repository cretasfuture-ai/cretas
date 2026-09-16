# -*- coding: utf-8 -*-
"""对外合同包：md -> docx（中文合同排版）-> 清洁。

清洁项与前几版一致：剥离内部属性、批注、修订标记、隐藏文字、外部链接。
"""
import os
import re
import subprocess
import sys
import zipfile

# 用法: python build_docx.py jobs.json
# jobs.json: {"base": "<md 源目录>", "out": "<docx 输出目录>",
#             "jobs": [["src.md", "out.docx", true], ...]}   # true = 长文件（附件各起一页，后续 to_pdf 加目录）
import json
_cfg = json.load(open(sys.argv[1], encoding="utf-8")) if len(sys.argv) > 1 else {}
BASE = _cfg.get("base", os.getcwd())
OUT = _cfg.get("out", os.path.join(BASE, "_outbound"))
SCRATCH = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(SCRATCH, "reference-cn.docx")

JOBS = [tuple(j) for j in _cfg.get("jobs", [])]


def strip_frontmatter(text):
    """去掉开头的 YAML 头，避免 pandoc 再生成一个重复的标题段和日期段。"""
    if not text.startswith("---"):
        return text, False
    m = re.match(r"^---\r?\n.*?\r?\n---\r?\n", text, re.S)
    if not m:
        return text, False
    return text[m.end():].lstrip("\r\n"), True


def strip_rules(text):
    """去掉独立成行的 `---` 分隔线。

    国内合同不在条与条之间画横线（那是网页/Markdown 的习惯）；章节边界靠黑体
    标题和分页体现。返回 (新文本, 去掉几条)。
    """
    lines = text.split("\n")
    keep, dropped = [], 0
    for ln in lines:
        if re.fullmatch(r"-{3,}\s*", ln):
            dropped += 1
            continue
        keep.append(ln)
    out = "\n".join(keep)
    # 去掉横线后可能留下连续空行，压成一个空行
    out = re.sub(r"\n{3,}", "\n\n", out)
    return out, dropped


# 条款起始：1.1 / 8.5.4 / 13.6 / R.2.1 / (a) / （a）
CLAUSE_START = re.compile(
    r"^ {0,3}("
    r"\d+\.\d+(?:\.\d+)*"        # 1.1、8.5.4、11.2.6
    r"|R\.\d+(?:\.\d+)*"         # R.2.1（共同条款）
    r"|\d+\."                    # 鉴于的 1. 2. 3. 4.
    r"|[（(][a-z]{1,4}[)）]"      # (a) (b) (c) (d) 以及罗马数字 (i) (ii) (iii) (iv) (v)
    r")[ 　]")

# 项目符号列表：紧跟在正文行后面时，Markdown 会把它并进上一段
# （实测「六膳门可以提供的支持：」下面四条被压成一整段，用 – 内联分隔）
BULLET_START = re.compile(r"^ {0,3}[-*+] \S")

# 上一行属于这些形态时不插空行：它们本身就是块的开头/表格/引用/列表
SKIP_PREV = re.compile(r"^\s*(\||#|>|[-*+] |\d+\. )")


def split_clauses(text):
    """让每一条条款各自成段。

    源文里条款是「一行一条、行间无空行」，Markdown 把它们当作软换行合成一个
    自然段 —— 实测 13.5～13.8 连同 (a)(b)(c)(d) 挤成一整段，对方律师读不下去。
    这里在条款号前补一个空行。返回 (新文本, 补了几处)。
    """
    lines = text.split("\n")
    out, added = [], 0
    for i, ln in enumerate(lines):
        prev = lines[i - 1] if i else ""
        starts_block = CLAUSE_START.match(ln) or BULLET_START.match(ln)
        if starts_block and prev.strip() and not SKIP_PREV.match(prev):
            out.append("")
            added += 1
        out.append(ln)
    return "\n".join(out), added


def clean_docx(path):
    """剥离一切不该出现在对外副本里的东西。返回各项计数。"""
    with zipfile.ZipFile(path) as z:
        items = [(i.filename, z.read(i.filename)) for i in z.infolist()]

    stat = {"dropped": [], "ins_del": 0, "vanish": 0, "extrel": 0, "forced_black": 0, "width_unlocked": 0}
    out = []
    for name, data in items:
        if name == "docProps/custom.xml" or name.endswith("comments.xml"):
            stat["dropped"].append(name)
            continue
        if name.endswith(".xml") or name.endswith(".rels"):
            s = data.decode("utf-8")
            n_id = len(re.findall(r"<w:(?:ins|del)\b", s))
            s = re.sub(r"<w:(ins|del)\b[^>]*/>", "", s)
            s = re.sub(r"<w:(ins|del)\b[^>]*>", "", s)
            s = re.sub(r"</w:(ins|del)>", "", s)
            stat["ins_del"] += n_id
            n_v = len(re.findall(r"<w:vanish\b", s))
            s = re.sub(r"<w:vanish\b[^>]*/>", "", s)
            stat["vanish"] += n_v
            # 外部关系（合同里不该有外链）
            n_e = len(re.findall(r'TargetMode="External"', s))
            stat["extrel"] += n_e
            # 表格列宽：pandoc 把所有列写成等宽并锁死布局 —— 实测「#」列和
            # 「一期交付范围」列一样宽，后者被挤成一条竖带。
            # ⚠️ 锁的写法不止一种：有的版本写每格 <w:tcW type="pct">，
            #    有的写 <w:tblLayout w:type="fixed"/> + 等分 <w:gridCol>。
            #    两种都要解开，否则 Word 的 AutoFit 根本不起作用（实测只堵了前一种，
            #    读数报「解锁 0 处」而列宽纹丝不动）。
            n_w = len(re.findall(r'<w:tcW\b[^>]*/>', s))
            s = re.sub(r'<w:tcW\b[^>]*/>', '', s)
            n_fx = len(re.findall(r'<w:tblLayout\b[^>]*?w:type="fixed"[^>]*>', s))
            s = re.sub(r'<w:tblLayout\b[^>]*?w:type="fixed"[^>]*>',
                       '<w:tblLayout w:type="autofit"/>', s)
            s = re.sub(r'<w:tblW\b[^>]*/>', '<w:tblW w:w="0" w:type="auto"/>', s)
            stat["width_unlocked"] = stat.get("width_unlocked", 0) + n_w + n_fx

            # 兜底：合同是黑白件，任何非黑颜色一律压黑
            n_c = len(re.findall(r'w:color w:val="(?!000000|auto)[0-9A-Fa-f]{6}"', s))
            s = re.sub(r'w:color w:val="(?!000000|auto)[0-9A-Fa-f]{6}"',
                       'w:color w:val="000000"', s)
            stat["forced_black"] = stat.get("forced_black", 0) + n_c
            # 清掉指向已删部件的引用
            s = re.sub(r'<Override PartName="/word/comments\.xml"[^>]*/>', "", s)
            s = re.sub(r'<Override PartName="/docProps/custom\.xml"[^>]*/>', "", s)
            s = re.sub(r'<Relationship [^>]*Target="comments\.xml"[^>]*/>', "", s)
            s = re.sub(r'<Relationship [^>]*Target="docProps/custom\.xml"[^>]*/>', "", s)
            s = re.sub(r'<Relationship [^>]*custom-properties[^>]*/>', "", s)
            data = s.encode("utf-8")
        out.append((name, data))

    tmp = path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in out:
            z.writestr(name, data)
    os.replace(tmp, path)
    return stat


def main():
    assert os.path.exists(REF), "模板缺失: " + REF
    os.makedirs(OUT, exist_ok=True)
    ok = True

    for src, dst, want_toc in JOBS:
        sp = os.path.join(BASE, src)
        assert os.path.exists(sp), "源缺失: " + sp
        text = open(sp, encoding="utf-8", newline="").read()
        body, stripped = strip_frontmatter(text)
        body, n_rules = strip_rules(body)
        body, n_split = split_clauses(body)
        # 分段只许改空白，不许动一个字：拿去掉全部空白的文本做同一性对照
        flat_before = re.sub(r"\s+", "", strip_rules(strip_frontmatter(text)[0])[0])
        flat_after = re.sub(r"\s+", "", body)
        assert flat_before == flat_after, "%s: 分段动到了正文内容" % src

        tmp_md = os.path.join(OUT, "_tmp_" + re.sub(r"[^0-9A-Za-z]", "", src)[:12] + ".md")
        open(tmp_md, "w", encoding="utf-8", newline="").write(body)

        dp = os.path.join(OUT, dst)
        # -fancy_lists：否则 pandoc 把行首的 (a) (b) 当成列表标记，把标签整个吃掉
        cmd = ["pandoc", tmp_md, "-f", "markdown-fancy_lists", "-t", "docx",
               "--no-highlight", "--reference-doc", REF, "-o", dp]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            print("PANDOC FAIL", dst, r.stderr[:400])
            ok = False
            continue

        st = clean_docx(dp)
        with zipfile.ZipFile(dp) as z:
            names = z.namelist()
            d = z.read("word/document.xml").decode("utf-8")
            sty = z.read("word/styles.xml").decode("utf-8")
        badcol = sorted(set(re.findall(
            r'w:color w:val="(?!000000|auto)([0-9A-Fa-f]{6})"', d + sty)))
        # 横线必须真的没了：pandoc 把 `---` 渲染成带下框线的空段落
        n_hr = len(re.findall(r'<w:pBdr>.*?<w:bottom\b', d, re.S))
        n_para = len(re.findall(r"<w:p\b", d))
        print("%-42s 去横线=%d(残留%d) 分段+%d 段落=%d 页脚=%s "
              "剥离=%d 修订=%d 隐藏=%d 外链=%d 压黑=%d 列宽解锁=%d 非黑色=%s"
              % (dst, n_rules, n_hr, n_split, n_para,
                 "word/footer1.xml" in names,
                 len(st["dropped"]), st["ins_del"], st["vanish"],
                 st["extrel"], st["forced_black"], st["width_unlocked"],
                 badcol if badcol else "无"))
        # 解锁之后不许还有锁死的表格布局（这道闸就是上一轮「解锁 0 处」漏掉的那一半）
        n_fixed = len(re.findall(r'w:tblLayout[^>]*w:type="fixed"', d))
        if n_fixed:
            print("     !! 仍有 %d 处表格布局被锁死" % n_fixed)
        if n_hr or n_fixed:
            ok = False
        if badcol or st["extrel"]:
            ok = False
        os.remove(tmp_md)

    print("\nBUILD_OK" if ok else "\nBUILD_HAS_ISSUES")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
