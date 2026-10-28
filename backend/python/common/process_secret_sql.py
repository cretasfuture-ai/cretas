"""工艺保密产品名的查询 (主库 process_secret_policies, Flyway V20261103_72)。

训练样本过滤 (smartbi.services.process_secret_names) 与外发脱敏 (common.llm_client) 共用一份;
放在 common 里, 外发脱敏不必为此加载整个 smartbi.services 包。
"""
from __future__ import annotations

from typing import FrozenSet, Iterable, Optional

MIN_NAME_LENGTH = 2

PROTECTED_NAMES_SQL = """
    SELECT DISTINCT pt.name
    FROM process_secret_policies p
    JOIN product_types pt ON pt.id::text = p.product_type_id AND pt.factory_id = p.factory_id
    WHERE p.factory_id = $1 AND pt.name IS NOT NULL
"""


_JAVA_TRIM = "".join(chr(c) for c in range(33))


def java_trim(s: str) -> str:
    """与 Java ``String.trim()`` 相同: 只去掉首尾 ≤ U+0020 的字符 (全角空格不去), 两边认的名字才一致。"""
    return s.strip(_JAVA_TRIM)


# Java Character.isWhitespace: Unicode 空白 (含全角空格) 但不含不换行空格
_JAVA_WHITESPACE = "".join(ch for ch in map(chr, range(0x3001)) if ch.isspace() and ch not in "   ")


def java_strip(s: str) -> str:
    """与 Java ``String.strip()`` 相同: 名字首尾的全角空格也去掉 (只认「名字 + 全角空格」会让正文里的名字原样发出)。"""
    return s.strip(_JAVA_WHITESPACE)


def usable_names(names: Iterable[Optional[str]]) -> FrozenSet[str]:
    return frozenset(java_strip(n) for n in names if n and len(java_strip(n)) >= MIN_NAME_LENGTH)
