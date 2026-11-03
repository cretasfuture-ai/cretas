"""训练样本的「本厂名单」匹配规则 (Steve 2026-10-29 选 A) —— Python 侧唯一的一份。

与 Java ``ProcessSecretTerms`` 同一规则; 两边都跑
``smartbi/services/process_secret_filter_rule_cases.json`` (同一份用例文件), 测试还比对两边的
``CATEGORY_WORDS``。改这里同时改 Java。

名单里有三类:

- 工艺保密产品的「保密词」: 只认全名 / 编码 / 产品 ID / 登记过的专属别名 (``secret_terms``)。
  纯数字的名字 (F006 有个产品叫 ``398``, 批次号里到处是 398) 与品类词 (牛肉、酱卤 …,
  以及这些保密产品自己的品类字段值) 不收;
- 本厂人员姓名 (``users.full_name``);
- 本厂客户名称 (``customers.name``)。

匹配一律是「原样出现」(不猜): 字母不分大小写 (只折叠 ASCII, 两边结果才一致), 汉字照原样比;
词的首 / 尾若是 ASCII 字母数字, 前 / 后紧挨着的字符就不能也是 ASCII 字母数字或下划线
(编码 ``PTF0060159`` 不会在 ``PTF00601590`` 里命中)。少于 2 个字的不收。

只给训练样本 / 学习 / 语料脚本用; 外发脱敏 (``common.llm_client``) 仍用 ``process_secret_sql`` 那一份,
不受本模块影响。纯函数, 只依赖标准库。
"""
from __future__ import annotations

import hashlib
import json
import re
from typing import Any, FrozenSet, Iterable, List, Mapping, Optional

from common.process_secret_sql import java_strip

MIN_TERM_LENGTH = 2

# 品类词: 产品 / 别名叫这个名字时不收 (它说的是一类东西, 不是这家工厂的那一个产品)。含单一部位与菜名
# (牛腱、鸭脖、米饭 …, Steve 2026-11-03 Q4 选 A)。词表只有一份: common/training_rule_words.json,
# Java 构建时把同一个文件打进 jar 读。另外每家工厂保密产品自己的 category / product_category 值也算品类词。
from common.training_values import WORDS as _RULE_WORDS  # noqa: E402

CATEGORY_WORDS = tuple(_RULE_WORDS["category_words"])

# 纯数字 (允许中间夹空格 / 点 / 横线 / 下划线 / 斜杠): 398、2024-01、１２３
_PURE_NUMERIC = re.compile(r"[0-9０-９ \t.\-_/　]+")

_ASCII_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
_FOLD = str.maketrans(_ASCII_UPPER, _ASCII_UPPER.lower())


def fold(text: str) -> str:
    """只把 ASCII 大写字母转小写 (与 Java ``ProcessSecretTerms.fold`` 一致)。"""
    return text.translate(_FOLD)


def _is_word_char(ch: str) -> bool:
    return ("a" <= ch <= "z") or ("A" <= ch <= "Z") or ("0" <= ch <= "9") or ch == "_"


def usable_term(raw: Optional[str], generic: FrozenSet[str] = frozenset()) -> Optional[str]:
    """名单里能用的那个词 (已去首尾空白、已折叠); 太短 / 纯数字 / 品类词返回 None。"""
    if raw is None:
        return None
    term = fold(java_strip(str(raw)))
    if len(term) < MIN_TERM_LENGTH or _PURE_NUMERIC.fullmatch(term) or term in generic:
        return None
    return term


def _generic_words(products: Iterable[Mapping[str, Any]]) -> FrozenSet[str]:
    words = {fold(w) for w in CATEGORY_WORDS}
    for p in products:
        for key in ("category", "product_category"):
            value = p.get(key)
            if value:
                words.add(fold(java_strip(str(value))))
    return frozenset(w for w in words if w)


def secret_terms(products: Iterable[Mapping[str, Any]],
                 aliases: Iterable[Optional[str]] = ()) -> FrozenSet[str]:
    """一家工厂的保密词: 每个保密产品的全名 / 编码 / ID, 加上登记过的专属别名。

    ``products`` 每项带 ``id`` / ``code`` / ``name`` / ``category`` / ``product_category``
    (缺的键当没有)。别名目前没有登记载体, 生产上恒为空 (见 PR 待决问题)。"""
    products = list(products)
    generic = _generic_words(products)
    out = set()
    for p in products:
        for key in ("name", "code", "id"):
            term = usable_term(p.get(key), generic)
            if term:
                out.add(term)
    for alias in aliases:
        term = usable_term(alias, generic)
        if term:
            out.add(term)
    return frozenset(out)


def policy_aliases(raw: Any) -> List[str]:
    """``process_secret_policies.aliases`` (JSON array text, Flyway V20261104_12) -> list.
    NULL / empty -> []. A malformed value raises: the caller's lookup fails (fail-closed)."""
    if raw is None or raw == "":
        return []
    value = json.loads(raw) if isinstance(raw, str) else raw
    if not isinstance(value, list):
        raise ValueError("process_secret_policies.aliases must be a JSON array")
    return [str(a) for a in value if a is not None]


def secret_terms_from_policy_rows(rows: Iterable[Mapping[str, Any]]) -> FrozenSet[str]:
    """Rows of ``PROTECTED_TERMS_SQL`` (product columns + the policy's ``aliases``)."""
    rows = list(rows)
    aliases: List[str] = []
    for r in rows:
        aliases += policy_aliases(r.get("aliases"))
    return secret_terms(rows, aliases)


def directory_names(names: Iterable[Optional[str]]) -> FrozenSet[str]:
    """人员姓名 / 客户名称: 去首尾空白、太短 / 纯数字的不收 (不套品类词)。"""
    return frozenset(t for t in (usable_term(n) for n in names) if t)


def _occurs(term: str, folded_text: str) -> bool:
    start = 0
    first_is_word = _is_word_char(term[0])
    last_is_word = _is_word_char(term[-1])
    while True:
        i = folded_text.find(term, start)
        if i < 0:
            return False
        j = i + len(term)
        before_ok = not (first_is_word and i > 0 and _is_word_char(folded_text[i - 1]))
        after_ok = not (last_is_word and j < len(folded_text) and _is_word_char(folded_text[j]))
        if before_ok and after_ok:
            return True
        start = i + 1


def mentions_any(terms: Iterable[str], *texts: Optional[str]) -> bool:
    """名单里任一词原样出现在任一文本里 (``terms`` 用 :func:`secret_terms` / :func:`directory_names` 造)。"""
    folded_terms = [fold(t) for t in terms if t]
    if not folded_terms:
        return False
    for text in texts:
        if not text:
            continue
        folded = fold(text)
        for term in folded_terms:
            if _occurs(term, folded):
                return True
    return False


def fingerprint(terms: Iterable[str]) -> str:
    """名单指纹 (排序后 sha256 前 16 位): 语料打分记下「按哪一版名单排除的」, 名单变了就重判。"""
    joined = "\n".join(sorted(set(terms)))
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()[:16]
