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

# 所有工厂的保密产品名 (并集): 服务端内部调用没带工厂时按它脱敏 (common.llm_client, 2026-10-29)
ALL_FACTORIES_PROTECTED_NAMES_SQL = """
    SELECT DISTINCT pt.name
    FROM process_secret_policies p
    JOIN product_types pt ON pt.id::text = p.product_type_id AND pt.factory_id = p.factory_id
    WHERE pt.name IS NOT NULL
"""


# ---- 训练样本的本厂名单 (common.process_secret_terms; Steve 2026-10-29 选 A) ----
# 外发脱敏不用这几条 (仍按上面的产品名)。名单 = 保密产品的全名 / 编码 / ID + 本厂人员姓名 + 本厂客户名称。
PROTECTED_TERMS_SQL = """
    SELECT DISTINCT pt.id::text AS id, pt.code, pt.name, pt.category, pt.product_category
    FROM process_secret_policies p
    JOIN product_types pt ON pt.id::text = p.product_type_id AND pt.factory_id = p.factory_id
    WHERE p.factory_id = $1
"""
ALL_PROTECTED_TERMS_SQL = """
    SELECT DISTINCT p.factory_id, pt.id::text AS id, pt.code, pt.name, pt.category, pt.product_category
    FROM process_secret_policies p
    JOIN product_types pt ON pt.id::text = p.product_type_id AND pt.factory_id = p.factory_id
"""
# 人员 / 客户: 含已软删除的行 (离职员工、停用客户的名字照样是本厂的人和客户)。与 Java
# FactoryNameDirectoryRepository 的两条原生查询同一口径。
FACTORY_DIRECTORY_SQL = """
    SELECT full_name AS name FROM users WHERE factory_id = $1 AND full_name IS NOT NULL
    UNION
    SELECT name FROM customers WHERE factory_id = $1 AND name IS NOT NULL
"""
ALL_FACTORY_DIRECTORY_SQL = """
    SELECT factory_id, full_name AS name FROM users WHERE full_name IS NOT NULL AND factory_id IS NOT NULL
    UNION
    SELECT factory_id, name FROM customers WHERE name IS NOT NULL AND factory_id IS NOT NULL
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
