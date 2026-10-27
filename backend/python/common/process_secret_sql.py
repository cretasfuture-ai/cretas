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


def usable_names(names: Iterable[Optional[str]]) -> FrozenSet[str]:
    return frozenset(n.strip() for n in names if n and len(n.strip()) >= MIN_NAME_LENGTH)
