"""发给外部大模型的文本: 与 Java ``LlmEgressRedactor`` 文本模式同一套规则、同一套代号 (Steve 2026-10-27)。

规则 (与 Java 逐条对齐, 两边共用 ``llm_egress_text_cases.json``):

- 手机号 (含 +86 / 横杠 / 点分 / 全角)、座机 (含三段 / 400)、身份证 (18 / 15 位 / 分组)、银行卡 (含分组)
  → ``[电话A]`` ``[证件号A]`` ``[卡号A]``;
- 调用方已知的交易对象 / 人 (``known_parties``) 与本厂工艺保密产品名 → ``[客户A]`` ``[供应商A]`` ``[单位A]``
  ``[人员A]`` ``[保密产品A]``, 单位名去掉后缀的简称也算同一个;
- ``detect_names``: 再认「公司类名称」「岗位 + 人名」「李经理」—— 历史 / 带数据的提示词要开; 只有用户原话、
  要按原话识别意图时关 (只抹号码), 与 Java ``LlmEgressGuard.utterance`` 一致;
- 入参里本来就写着的代号字样不再分配 (还原时不会错换); 发出前再扫一遍, 残留就拒绝 (``refusal``, 只有类别)。

还原 (``restore``) 认模型常见的改写: 去括号、全角括号、括号里加空格; 只换回本次请求发出去的代号。
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Callable, Dict, Iterable, List, Mapping, Optional, Sequence

from common.sample_text_rules import (  # 训练规则常量: 与 Java TrainingSampleRules 逐字对齐
    _BANK_CARD_RE as BANK_CARD,
    _HAN as HAN,
    _ID_CARD_RE as ID_CARD,
    _ORG_NAME_RE as ORG_NAME,
    _PERSON_RE as PERSON,
    _PERSON_ROLE as PERSON_ROLE,
    _PHONE_RE as PHONE,
    _SURNAMES as SURNAMES,
)

MIN_NAME_LENGTH = 2

# 种类与代号里的类别名 (顺序与 Java Kind 枚举一致: 正则里的候选顺序也一致)
CUSTOMER, SUPPLIER, ORG, PERSON_KIND, NAME, PRODUCT, PHONE_KIND, ID_NUMBER, CARD, HIDDEN_FIELD = (
    "客户", "供应商", "单位", "人员", "名称", "保密产品", "电话", "证件号", "卡号", "隐藏内容")
KINDS = (CUSTOMER, SUPPLIER, ORG, PERSON_KIND, NAME, PRODUCT, PHONE_KIND, ID_NUMBER, CARD, HIDDEN_FIELD)
PARTIES = {"CUSTOMER": CUSTOMER, "SUPPLIER": SUPPLIER, "ORG": ORG, "PERSON": PERSON_KIND}

GENERIC = frozenset({"系统", "管理员", "平台", "未知", "无", "暂无", "未分配", "默认",
                     "全部", "其他", "其它", "本厂", "admin", "system", "unknown", "N/A", "null", "-", "—"})

_A = re.ASCII
MOBILE_LOOSE = re.compile(r"(?<![0-9])(?:\+?86[- ]?)?1[3-9]\d[- ]?\d{4}[- ]?\d{4}(?![0-9])", _A)
LANDLINE = re.compile(r"(?<![0-9])(?:\(0\d{2,3}\)\s?|0\d{2,3}[- ]?)\d{7,8}(?![0-9])", _A)
EXTRA_SURNAMES = "牛常欧司诸上于向付万傅齐葛祁倪柳毕甘施尚屈翁庞裴伍俞阮聂单"
TITLED_NAME = re.compile(rf"[{SURNAMES}{EXTRA_SURNAMES}][{HAN}]?"
                         r"(?:经理|总监|老板|师傅|厂长|主任|科长|部长|组长|班长|主管|先生|女士|小姐)")
_INDUSTRY = r"(?:牧业|肉业|肉联厂|合作社|养殖场|养殖基地|农场|冷链|物流|餐饮|贸易|商店|酒楼|批发市场)"
ORG_NAME_EXTRA = re.compile(rf"[{HAN}A-Za-z0-9]{{2,12}}{_INDUSTRY}")
ORG_LEAD_IN = re.compile(r"^.*(?:一下|我们是|我们|是|给|向|从|找|查|问|跟|让|叫|请|把|往|跟进|联系|通知|客户|供应商)")
GENERIC_INDUSTRY_PHRASE = re.compile(
    r"(?:本周|上周|下周|本月|上月|下月|今年|去年|本年|今天|昨天|明天|本季度|上季度|近期|最近|当前|目前|"
    r"这批|那批|这些|那些|所有|全部|整体|总体|各个|各|我们的|我们|我方|我司|公司|工厂|本厂|"
    r"查看|查询|查一下|看看|看一下|统计|分析|对比|汇总)+" + _INDUSTRY)
SINGLE_CHAR_ORG_LEAD_INS = "给让请把往"
PERSON_LEAD_IN = re.compile(rf"^(?:{PERSON_ROLE}|联系|找|通知|叫|告诉)\s*[:：|]?\s*", _A)
CARD_GROUPED = re.compile(r"(?<![0-9])\d{4}(?:[ -]\d{4}){3}(?:[ -]?\d{1,3})?(?![0-9])", _A)
ID_GROUPED = re.compile(r"(?<![0-9])\d{6}[ -]\d{8}[ -]\d{3}[\dXx](?![0-9A-Za-z])", _A)
ID_15 = re.compile(r"(?<![0-9A-Za-z])\d{15}(?![0-9A-Za-z])", _A)
LANDLINE_SEGMENTED = re.compile(r"(?<![0-9])(?:0\d{2,3}|400|800)[- ]\d{3,4}[- ]\d{4}(?![0-9])", _A)
MOBILE_DOTTED = re.compile(r"(?<![0-9])(?:\+?86[ .]?)?1[3-9]\d\.\d{4}\.\d{4}(?![0-9])", _A)
MOBILE_FULLWIDTH = re.compile(r"(?<![０-９])１[３-９][０-９]{9}(?![０-９])")
ORG_SUFFIX_STRIP = re.compile(
    r"(?:股份有限公司|有限责任公司|有限公司|公司|集团|超市|商贸|商行|批发部|门市部|连锁|酒店|饭店|食品厂|牧业|肉业|肉联厂"
    r"|合作社|养殖场|农场|冷链|物流|餐饮|贸易|商店|酒楼|食品|科技)$")
CODE_LIKE = re.compile(r"^[A-Za-z0-9_\-./:#+ %,¥$]{1,64}$")

_KIND_LABELS = "|".join(re.escape(k) for k in KINDS)
BARE_TOKEN_CLASSIFIERS = "类级班组区栋座型款档线号层楼室段队"
LOOSE_TOKEN = re.compile(
    rf"[\[【［][ \t]*({_KIND_LABELS})[ \t]*([A-Z]+)[ \t]*[\]】］]"
    rf"|({_KIND_LABELS})([A-Z]+)(?![A-Za-z0-9{BARE_TOKEN_CLASSIFIERS}])")
_KIND_LABEL_PREFIXES = "|".join(re.escape(k[:n]) for k in KINDS for n in range(1, len(k) + 1))
PARTIAL_TOKEN_TAIL = re.compile(
    rf"(?:[\[【［][ \t]*(?:(?:{_KIND_LABEL_PREFIXES})(?:[ \t]*[A-Z]+)?[ \t]*)?"
    rf"|(?:{_KIND_LABEL_PREFIXES})[A-Z]*)$")

_NUMERIC_KINDS = (PHONE_KIND, CARD, ID_NUMBER)


def canonical_token(m: "re.Match") -> str:
    return f"[{m.group(1)}{m.group(2)}]" if m.group(1) is not None else f"[{m.group(3)}{m.group(4)}]"


def letters(n: int) -> str:
    """1→A, 26→Z, 27→AA。"""
    out = ""
    while n > 0:
        n -= 1
        out = chr(ord("A") + n % 26) + out
        n //= 26
    return out


def _json_escaped(real: str) -> str:
    return json.dumps(real, ensure_ascii=False)[1:-1]


def _forms(real: str) -> List[str]:
    """原样, 以及放进 JSON 字符串后的写法 (换行 / 引号 / 反斜杠被转义); 中文不变。"""
    escaped = _json_escaped(real)
    return [real] if escaped == real else [real, escaped]


def _is_ascii(s: str) -> bool:
    return all(ord(c) < 128 for c in s)


def _ascii_word(real: str) -> "re.Pattern":
    return re.compile(r"(?<![A-Za-z0-9_])" + re.escape(real) + r"(?![A-Za-z0-9_])")


def _after_decimal_point(text: str, start: int) -> bool:
    return start >= 2 and text[start - 1] == "." and text[start - 2].isdigit()


def _find_number(pattern: "re.Pattern", text: str) -> bool:
    return any(not _after_decimal_point(text, m.start()) for m in pattern.finditer(text))


def _is_digits(s: str) -> bool:
    return re.fullmatch(r"\d+", s, _A) is not None


def is_code(v: str) -> bool:
    return CODE_LIKE.fullmatch(v) is not None


def _org_core(span: str) -> Optional[str]:
    """「查一下永辉超市」→「永辉超市」; 去掉之后不再像单位名就保留整段; 时间 / 动词 + 行业词不是单位名。"""
    lead = ORG_LEAD_IN.search(span)
    name = span
    if lead and (lead.end() >= 2 or span[0] in SINGLE_CHAR_ORG_LEAD_INS) and lead.end() < len(span):
        rest = span[lead.end():]
        if ORG_NAME.fullmatch(rest) or ORG_NAME_EXTRA.fullmatch(rest):
            name = rest
    return None if GENERIC_INDUSTRY_PHRASE.fullmatch(name) else name


def _person_core(span: str) -> str:
    """「质检员：赵铁柱」「通知张三」→ 只取名字。"""
    lead = PERSON_LEAD_IN.search(span)
    if lead and lead.end() < len(span) and len(span) - lead.end() >= 2:
        return span[lead.end():]
    return span


@dataclass
class Result:
    texts: List[Optional[str]] = field(default_factory=list)
    token_to_real: Dict[str, str] = field(default_factory=dict)
    refusal: Optional[str] = None

    @property
    def refused(self) -> bool:
        return self.refusal is not None

    @property
    def token_count(self) -> int:
        return len(self.token_to_real)

    def restore(self, text: Optional[str]) -> Optional[str]:
        return restore(text, self.token_to_real)

    def restore_json_text(self, text: Optional[str]) -> Optional[str]:
        return restore(text, self.token_to_real, encode=_json_escaped)


def restore(text: Optional[str], token_to_real: Mapping[str, str],
            encode: Callable[[str], str] = lambda r: r) -> Optional[str]:
    """把模型回答里的代号换回真名 (去括号 / 全角括号 / 括号里加空格都认); 只认 ``token_to_real`` 里的。"""
    if not text or not token_to_real:
        return text

    def _sub(m: "re.Match") -> str:
        real = token_to_real.get(canonical_token(m))
        return encode(real) if real is not None else m.group(0)

    return LOOSE_TOKEN.sub(_sub, text)


class _Session:
    def __init__(self, protected_names: Optional[Iterable[str]], reserved: str) -> None:
        self.real_to_token: Dict[str, str] = {}
        self.token_to_real: Dict[str, str] = {}
        self.real_kind: Dict[str, str] = {}
        self.counters: Dict[str, int] = {}
        names = [n.strip() for n in (protected_names or ()) if n and len(n.strip()) >= MIN_NAME_LENGTH]
        self.protected_names = sorted(names, key=len, reverse=True)
        self.explicit_reals: set = set()
        self.reserved = {canonical_token(m) for m in LOOSE_TOKEN.finditer(reserved)}

    def token(self, kind: str, real: str) -> str:
        existing = self.real_to_token.get(real)
        if existing is not None:
            return existing
        while True:
            n = self.counters.get(kind, 0) + 1
            self.counters[kind] = n
            t = f"[{kind}{letters(n)}]"
            if t not in self.reserved:
                break
        self.real_to_token[real] = t
        self.token_to_real[t] = real
        self.real_kind[real] = kind
        return t

    def known(self, kind: str, real: str) -> None:
        t = self.token(kind, real)
        self.explicit_reals.add(real)
        if kind != PERSON_KIND:
            core = real
            m = ORG_SUFFIX_STRIP.search(core)
            while m:
                core = core[:m.start()]
                if len(core) < 3:
                    break
                self._alias(core, t, kind)
                m = ORG_SUFFIX_STRIP.search(core)
        escaped = _json_escaped(real)
        if escaped != real:
            self._alias(escaped, t, kind)

    def _alias(self, alias: str, token: str, kind: str) -> None:
        if alias not in self.real_to_token and alias not in GENERIC:
            self.real_to_token[alias] = token
            self.real_kind[alias] = kind
            self.explicit_reals.add(alias)

    def replace_spans(self, text: str, pattern: "re.Pattern", kind: str,
                      core: Callable[[str], Optional[str]] = lambda s: s) -> str:
        numeric = kind in _NUMERIC_KINDS

        def _sub(m: "re.Match") -> str:
            span = m.group(0)
            if numeric and _after_decimal_point(text, m.start()):
                return span  # 0.0523809524 的小数部分不是号码
            name = core(span)
            if name is None:
                return span
            return span[:len(span) - len(name)] + self.token(kind, name)

        return pattern.sub(_sub, text)

    def replaceable_everywhere(self, real: str) -> bool:
        if real in self.explicit_reals:
            return len(real) >= 2 and not _is_digits(real)
        kind = self.real_kind.get(real)
        if kind is None or kind in (NAME, HIDDEN_FIELD) or len(real) < 2 or _is_digits(real):
            return False
        return not is_code(real) or (len(real) >= 3 and re.search(r"[A-Za-z]", real) is not None)

    def spreadable_reals(self) -> List[str]:
        return sorted((r for r in self.real_to_token if self.replaceable_everywhere(r)), key=len, reverse=True)

    def replace_known_text(self, text: str, reals: Sequence[str]) -> str:
        if text in self.token_to_real:
            return text
        out = text
        for name in self.protected_names:
            if name in out:
                out = out.replace(name, self.token(PRODUCT, name))
        for real in reals:
            for form in _forms(real):
                if form not in out:
                    continue
                token = self.real_to_token[real]
                out = _ascii_word(form).sub(lambda _m: token, out) if _is_ascii(form) else out.replace(form, token)
        return out

    def scrub_text(self, value: str, detect_names: bool) -> str:
        out = self.replace_known_text(value, self.spreadable_reals())
        out = self.replace_spans(out, ID_GROUPED, ID_NUMBER)
        out = self.replace_spans(out, ID_CARD, ID_NUMBER)
        out = self.replace_spans(out, CARD_GROUPED, CARD)
        out = self.replace_spans(out, BANK_CARD, CARD)
        out = self.replace_spans(out, ID_15, ID_NUMBER)
        out = self.replace_spans(out, MOBILE_FULLWIDTH, PHONE_KIND)
        out = self.replace_spans(out, MOBILE_DOTTED, PHONE_KIND)
        out = self.replace_spans(out, MOBILE_LOOSE, PHONE_KIND)
        out = self.replace_spans(out, LANDLINE_SEGMENTED, PHONE_KIND)
        out = self.replace_spans(out, LANDLINE, PHONE_KIND)
        out = self.replace_spans(out, PHONE, PHONE_KIND)
        if detect_names:
            # 公司名 / 人名的匹配会带上前面的动词或岗位, 只把名字本身换成代号, 前缀原样留着
            out = self.replace_spans(out, ORG_NAME, ORG, _org_core)
            out = self.replace_spans(out, ORG_NAME_EXTRA, ORG, _org_core)
            out = self.replace_spans(out, PERSON, PERSON_KIND, _person_core)
            out = self.replace_spans(out, TITLED_NAME, PERSON_KIND)
        return out

    def find_leak(self, texts: Iterable[Optional[str]]) -> Optional[str]:
        """发出前最后一遍: 残留号码 / 真名 / 保密产品名 → 原因 (不含原始值); 干净返回 None。"""
        for s in texts:
            if s is None or s in self.token_to_real:
                continue
            if (_find_number(PHONE, s) or _find_number(MOBILE_LOOSE, s) or _find_number(MOBILE_DOTTED, s)
                    or MOBILE_FULLWIDTH.search(s)):
                return "残留手机号"
            if _find_number(ID_CARD, s):
                return "残留证件号"
            if is_code(s):
                if re.fullmatch(r"\(?0\d{2,3}\)?[- ]?\d{7,8}", s.strip(), _A):
                    return "残留电话号码"
            else:
                if _find_number(LANDLINE, s) or _find_number(LANDLINE_SEGMENTED, s):
                    return "残留电话号码"
                if _find_number(BANK_CARD, s) or _find_number(CARD_GROUPED, s):
                    return "残留银行卡号"
                if _find_number(ID_GROUPED, s) or _find_number(ID_15, s):
                    return "残留证件号"
            for name in self.protected_names:
                if name in s:
                    return "残留工艺保密产品名"
            for real in self.real_to_token:
                if self.replaceable_everywhere(real) and self._still_contains(s, real):
                    return "残留已脱敏的名称"
        return None

    @staticmethod
    def _still_contains(s: str, real: str) -> bool:
        return any(_ascii_word(f).search(s) if _is_ascii(f) else f in s for f in _forms(real))


def redact_texts(texts: Sequence[Optional[str]], known_parties: Optional[Mapping[str, str]] = None,
                 protected_names: Optional[Iterable[str]] = None, detect_names: bool = True) -> Result:
    """与 Java ``LlmEgressRedactor.redactTexts`` 相同。

    :param known_parties: ``{真名: "CUSTOMER" | "SUPPLIER" | "ORG" | "PERSON"}`` (调用方从记录里取出的名字)
    """
    corpus = "".join(t + "\n" for t in texts if t is not None)
    s = _Session(protected_names, corpus)
    for real, party in (known_parties or {}).items():
        real = (real or "").strip()
        if len(real) >= 2 and real not in GENERIC:
            s.known(PARTIES[party], real)
    scrubbed = [None if t is None else s.scrub_text(t, detect_names) for t in texts]
    # 第二遍: 后面才认出的真名, 在前面的段落里也换掉
    reals = s.spreadable_reals()
    cleaned = [None if t is None else s.replace_known_text(t, reals) for t in scrubbed]
    leak = s.find_leak(cleaned)
    if leak is not None:
        return Result(refusal=leak)
    return Result(texts=cleaned, token_to_real=dict(s.token_to_real))


class StreamRestorer:
    """流式回答: 代号可能被拆在两段里 (``"[客"`` + ``"户A]"``), 攒到闭合再换回真名 (同 Java)。"""

    MAX_PENDING = 16

    def __init__(self, token_to_real: Mapping[str, str]) -> None:
        self._map = token_to_real
        self._pending = ""

    def push(self, chunk: Optional[str]) -> str:
        if not chunk:
            return ""
        if not self._map:
            return chunk  # 本次没发代号: 原样放行, 不攒
        self._pending += chunk
        p = self._pending
        open_ = p.rfind("[")
        cut = open_ if open_ >= 0 and p.find("]", open_) < 0 and len(p) - open_ <= self.MAX_PENDING else len(p)
        tail = PARTIAL_TOKEN_TAIL.search(p, max(0, len(p) - self.MAX_PENDING))
        if tail:
            cut = min(cut, tail.start())
        if cut <= 0:
            return ""
        ready, self._pending = p[:cut], p[cut:]
        return restore(ready, self._map) or ""

    def flush(self) -> str:
        rest, self._pending = self._pending, ""
        return restore(rest, self._map) or ""
