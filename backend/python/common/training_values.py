"""「词 + 具体数值」规则 (Steve 2026-10-29 选 A; 2026-11-03 Q2/Q3 + 安全复审 B1/N1) —— Python 侧唯一的一份。

与 Java ``TrainingValueRules`` 逐步相同, 两边跑同一份
``smartbi/services/process_secret_filter_rule_cases.json``; 词表在 ``common/training_rule_words.json``
(两边读同一个文件)。改这里同时改 Java。

一个词 (工艺 / 配方 / 人员薪酬词) 只有和**具体数值**一起出现才拦:

* 数值: 阿拉伯 / 全角数字 (贴着字母的也算: pH4、4.2pH、30min), 中文数字 (一百二十一、三十、
  一点五、三比一 / 一比二十、万分之五, 含大写 壹贰叁; 两个字以上, 或后面跟单位 —— 「一下」「一起」
  「半成品」这类常用词不算, 但常用词其实是数值的一部分时照算: 「十分钟」「万分之五」);
* 距离: 数值与词在同一条记录里, 且相距不超过 12 个字 (``WINDOW``); 窗口里什么都不剥
  (编号、日期、时刻都算数值: 「配比 1:20」「转速 1500.5」不会被当成时刻 / 日期放过), 只有每行
  开头的列表序号 (「6. 」「一、」) 不算;
* 记录: 一句话; 一个列表项连同缩进的续行; 表格整张 (表头某列是工艺词, 数据行同一列有数值就拦);
  含工艺词的标题 (冒号结尾, 或 Markdown ``#`` 标题) 连同它下面的内容: 紧跟的第一行、之后的列表 /
  缩进行 / 冒号小标题, 可隔空行, 到下一个 ``#`` 标题或下一段正文为止 (里面有数值就拦, 不受 12 字限制;
  标题里只有条件词 (「统计时间：」「### 交货时间」) 时, 里面要有「数值 + 单位」才拦);
  判断前统一换行 (\r\n) 并去掉 Markdown 加粗 (``**`` / ``__``);
* 条件词: 温度 / 时间 / 压力 / 转速 / 浓度 后面 12 个字内出现「数值 + 单位」(121℃、30 分钟、0.2MPa) 才算。

位置一律按 Unicode 码点计 (Java 侧同样换算), 两边才一致。纯函数, 只依赖标准库。
"""
from __future__ import annotations

import json
import os
import re
from typing import Iterable, List, Optional, Sequence, Tuple

WINDOW = 12

_WORDS_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "training_rule_words.json")
with open(_WORDS_PATH, encoding="utf-8") as _f:
    WORDS = json.load(_f)

CONDITIONAL_MARKERS: Tuple[str, ...] = tuple(WORDS["conditional_markers"])
VALUE_UNITS: Tuple[str, ...] = tuple(sorted(WORDS["value_units"], key=len, reverse=True))
CN_UNITS: Tuple[str, ...] = tuple(sorted(WORDS["cn_units"], key=len, reverse=True))
CN_STOP_PHRASES: Tuple[str, ...] = tuple(sorted(WORDS["cn_stop_phrases"], key=len, reverse=True))
GLUED_UNIT_PREFIXES: Tuple[str, ...] = tuple(sorted(WORDS["glued_unit_prefixes"], key=len, reverse=True))

# 中文数字含大写 (财务写法 壹贰叁 …, 复审第二轮 B1)
_CN = "零〇一二三四五六七八九十百千万两半壹贰叁肆伍陆柒捌玖拾佰仟萬貳參陸"
_CN_NUM = f"[{_CN}]+(?:点[{_CN}]+)?"
CN_RATIO_RE = re.compile(f"{_CN_NUM}比{_CN_NUM}")
# 分数: 万分之五、百分之三、千分之二 (自带单位: 条件词后面不必再跟单位)
CN_FRACTION_RE = re.compile(f"{_CN_NUM}分之{_CN_NUM}")
CN_NUM_RE = re.compile(_CN_NUM)
DIGIT_NUM_RE = re.compile(r"[0-9０-９]+(?:[.．][0-9０-９]+)?")
# 每行开头的列表序号 (不算数值): 「6. 」「### 3、」「- 2) 」「一、」
LINE_ORDINAL_RE = re.compile(
    r"^[ \t　]*(?:#+[ \t　]*)?(?:[-*•][ \t　]*)?(?:\*\*)?(?:[0-9]{1,3}|[一二三四五六七八九十]{1,3})[.、)）](?![0-9])",
    re.M)
SENTENCE_SPLIT_RE = re.compile(r"[。！？!?；;]")
_LIST_LINE_RE = re.compile(r"^(?:[-*•]|[0-9]{1,3}[.、)）]|[一二三四五六七八九十]{1,3}、)")
_SEPARATOR_CELL_RE = re.compile(r"^[ \t:：\-—]*$")
_SPACE = " \t　"


def _blank(s: str, start: int, end: int) -> str:
    return s[:start] + " " * (end - start) + s[end:]


def _leading_cn(phrase: str) -> int:
    k = 0
    while k < len(phrase) and phrase[k] in _CN:
        k += 1
    return k


def _keeps_stop_phrase(s: str, i: int, phrase: str, fractions: Sequence[Tuple[int, int]]) -> bool:
    """A stop phrase that is really part of a value is not blanked (复审第二轮 B1): preceded by a
    Chinese numeral (「三十分钟」里的「十分」、「三万一」里的「万一」), its numeral head followed by a
    unit (「十分钟」= 十 + 分钟), or inside a fraction (「万分之五」)."""
    if i > 0 and s[i - 1] in _CN:
        return True
    k = _leading_cn(phrase)
    if k and _starts_with_any(s, i + k, VALUE_UNITS):
        return True
    return any(a <= i < b for a, b in fractions)


def _prepared(text: str) -> str:
    """Stop phrases and line-leading ordinals blanked out (same length, positions kept)."""
    s = text
    fractions = [(m.start(), m.end()) for m in CN_FRACTION_RE.finditer(text)]
    for phrase in CN_STOP_PHRASES:
        i = s.find(phrase)
        while i >= 0:
            if not _keeps_stop_phrase(s, i, phrase, fractions):
                s = _blank(s, i, i + len(phrase))
            i = s.find(phrase, i + len(phrase))
    for m in list(LINE_ORDINAL_RE.finditer(s)):
        s = _blank(s, m.start(), m.end())
    return s


def _starts_with_any(s: str, at: int, words: Sequence[str]) -> bool:
    j = at
    while j < len(s) and s[j] in _SPACE:
        j += 1
    return any(s.startswith(w, j) for w in words)


def value_spans(text: str, *, cn_only: bool = False) -> List[Tuple[int, int]]:
    """Concrete-number spans of ``text`` (positions in ``text``)."""
    s = _prepared(text)
    spans: List[Tuple[int, int]] = []
    if not cn_only:
        spans += [(m.start(), m.end()) for m in DIGIT_NUM_RE.finditer(s)]
    ratio = [(m.start(), m.end()) for m in CN_RATIO_RE.finditer(s)]
    ratio += [(m.start(), m.end()) for m in CN_FRACTION_RE.finditer(s)]
    spans += ratio
    for m in CN_NUM_RE.finditer(s):
        if any(a <= m.start() < b for a, b in ratio):
            continue
        if len(m.group()) >= 2 or _starts_with_any(s, m.end(), CN_UNITS):
            spans.append((m.start(), m.end()))
    return sorted(set(spans))


def _occurrences(s: str, word: str) -> Iterable[Tuple[int, int]]:
    i = s.find(word)
    while i >= 0:
        yield i, i + len(word)
        i = s.find(word, i + 1)


def window_hit(s: str, markers: Sequence[str], conditional: Sequence[str] = ()) -> bool:
    """A marker with a value within ``WINDOW`` characters (either side), or a conditional
    marker followed within ``WINDOW`` characters by a value + unit."""
    if not s:
        return False
    spans = None
    for m in markers:
        for i, j in _occurrences(s, m):
            spans = value_spans(s) if spans is None else spans
            if any(a < j + WINDOW and b > i - WINDOW for a, b in spans):
                return True
    for c in conditional:
        for _i, j in _occurrences(s, c):
            spans = value_spans(s) if spans is None else spans
            if any(j <= a < j + WINDOW and (_starts_with_any(s, b, VALUE_UNITS) or "分之" in s[a:b])
                   for a, b in spans):
                return True
    return False


def _is_table_line(line: str) -> bool:
    return line.strip(_SPACE).startswith("|")


def _is_list_or_indented(line: str) -> bool:
    if not line:
        return False
    return line[0] in _SPACE or bool(_LIST_LINE_RE.match(line.strip(_SPACE)))


_EMPHASIS_RE = re.compile(r"\*\*|__")


def _normalize(text: str) -> str:
    """CRLF / CR -> LF; Markdown emphasis markers (``**`` / ``__``) dropped (复审第二轮 N1)."""
    return _EMPHASIS_RE.sub("", text.replace("\r\n", "\n").replace("\r", "\n"))


def _heading_core(line: str) -> Tuple[str, bool]:
    t = line.strip(_SPACE)
    if t.startswith("#"):
        return t.lstrip("#").strip(_SPACE), True
    return t, False


def _is_heading(line: str, words: Sequence[str]) -> bool:
    """A process-word heading: a Markdown heading (``### 配方``) or a line ending in a colon."""
    t, markdown = _heading_core(line)
    return bool(t) and (markdown or t[-1] in "：:") and any(w in t for w in words)


def _has_unit_value(s: str) -> bool:
    """A value followed by a unit (``VALUE_UNITS``), or a fraction (自带单位)."""
    return any(_starts_with_any(s, b, VALUE_UNITS) or "分之" in s[a:b] for a, b in value_spans(s))


def _heading_end(lines: List[str], i: int) -> int:
    """End (exclusive) of the block under the heading at ``i`` (复审第二轮 N1): the first non-blank
    line after it always belongs to it; after that list / indented lines and colon-ended
    sub-headings, across blank lines. It stops at the next Markdown heading or at a further
    plain paragraph."""
    j = i + 1
    end = j
    first = True
    while j < len(lines):
        line = lines[j]
        t, markdown = _heading_core(line)
        if not t:
            j += 1
            continue
        if markdown:
            break
        if first or _is_list_or_indented(line) or t[-1] in "：:":
            j += 1
            end = j
            first = False
            continue
        break
    return end


def _cells(line: str) -> List[str]:
    t = line.strip(_SPACE)
    if t.startswith("|"):
        t = t[1:]
    if t.endswith("|"):
        t = t[:-1]
    return [c.strip(_SPACE) for c in t.split("|")]


def _table_hit(rows: List[str], markers: Sequence[str], conditional: Sequence[str]) -> bool:
    for row in rows:
        if window_hit(row, markers, conditional):
            return True
    header = _cells(rows[0])
    data = [_cells(r) for r in rows[1:] if not all(_SEPARATOR_CELL_RE.match(c) for c in _cells(r))]
    for k, cell in enumerate(header):
        if any(m in cell for m in markers) or any(c in cell for c in conditional):
            if any(k < len(r) and value_spans(r[k]) for r in data):
                return True
    return False


def has_word_with_value(text: Optional[str], markers: Sequence[str], conditional: Sequence[str] = ()) -> bool:
    """The rule: see the module docstring."""
    if not text:
        return False
    lines = _normalize(text).split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        if _is_table_line(line):
            j = i
            while j < len(lines) and _is_table_line(lines[j]):
                j += 1
            if _table_hit(lines[i:j], markers, conditional):
                return True
            i = j
            continue
        j = i + 1
        if _is_heading(line, tuple(markers) + tuple(conditional)):
            j = _heading_end(lines, i)
            body = "\n".join(lines[i + 1:j])
            # a heading named only by a bare conditional word (「统计时间：」「### 交货时间」) is generic:
            # its body needs a value WITH a unit, like the inline rule (review round 3, item 1)
            strong = _is_heading(line, tuple(m for m in markers if m not in conditional))
            if body and (value_spans(body) if strong else _has_unit_value(body)):
                return True
        else:
            while j < len(lines) and lines[j][:1] in tuple(_SPACE) and lines[j]:
                j += 1
        block = "\n".join(lines[i:j])
        for sentence in SENTENCE_SPLIT_RE.split(block):
            if window_hit(sentence, markers, conditional):
                return True
        i = j
    return False


_GLUED_RE = re.compile("(?<![A-Za-z])(" + "|".join(re.escape(p) for p in GLUED_UNIT_PREFIXES) + r")([0-9]+(?:\.[0-9]+)?)")
# 全角数字也遮, 「<5」里的 5 也遮 (复审第二轮 B1); 编号 (F006 / Ｆ００６ / V2) 照旧保留
_NUMBER_RE = re.compile(r"(?<![A-Za-z0-9Ａ-Ｚａ-ｚ０-９_.．])[0-9０-９]+(?:[.．][0-9０-９]+)?")


def mask_values(text: str) -> str:
    """Every value -> ``<数值>``: Arabic numbers (codes like F006 / V2 / B12 kept), numbers
    glued after a unit (pH4 -> pH<数值>), and Chinese-numeral values (三十 / 一百二十一 / 三比一)."""
    out = _GLUED_RE.sub(lambda m: m.group(1) + "<数值>", text)
    out = _NUMBER_RE.sub("<数值>", out)
    for a, b in reversed(value_spans(out, cn_only=True)):
        out = out[:a] + "<数值>" + out[b:]
    return out
