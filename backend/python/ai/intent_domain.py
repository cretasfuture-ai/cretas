"""业务域缩小候选 (意图蒸馏第③步 PR-4, 方案第 2 节第 1 层)。

先判这句话属于哪个 (或哪几个) 业务域, 再只把这些域的意图交给决策模型的粗选 / 合并理解提示词。
业务域表是 Java 资源 ``ai/intent-domain-table.json`` 的同步副本 (``ai/data/intent-domain-table.json``,
逐字节相同由 tests/test_intent_domain_scope_contract.py 钉住), 不是第二个真源。

兜底规则 (表第 1、3、6 节写死, 这里逐条落实):
  1. 查不到业务域的意图 (工厂自建等, 不在表里) 永远留在候选里 —— 走语义召回兜底, 不当成某个域。
  2. SYSTEM / ANALYTICS 是跨域通用入口, 不因为「域已确定」被排除。
  3. 主域或次域 (``secondary_domain``) 命中都算。
  4. 判不出来 / 判出的域太多 / 带上下文的轮次 / Java 没带或带错目录版本 ⇒ 不收窄 (宁可放宽)。
  5. 业务域不授权任何东西: 这里只决定提示词里列哪些意图; 精确短语表、语义召回候选、权限、写确认、审批
     全按原链路走 (调用方不得用本模块的结果替代任何闸)。

本模块不导入数据库 / 模型 / FastAPI, 离线评测与运行时用同一份逻辑。
"""
from __future__ import annotations

import json
import unicodedata
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict, FrozenSet, Iterable, List, Mapping, Optional, Sequence

PACKAGED_TABLE_PATH = Path(__file__).resolve().parent / "data" / "intent-domain-table.json"
JAVA_TABLE_PATH = (
    Path(__file__).resolve().parents[3]
    / "backend" / "java" / "cretas-api" / "src" / "main" / "resources" / "ai" / "intent-domain-table.json"
)

# 跨域通用入口: 不因为「域已确定」被排除 (表第 3 节第 84 行)。
ALWAYS_CANDIDATE_DOMAINS: FrozenSet[str] = frozenset({"SYSTEM", "ANALYTICS"})
# 一句话命中这么多个业务域 ⇒ 当作说不清, 不收窄。
MAX_DETECTED_DOMAINS = 4

REASON_NARROWED = "narrowed"
REASON_NO_DOMAIN = "no_domain"
REASON_TOO_BROAD = "too_broad"
REASON_CONTEXTUAL = "contextual"
REASON_NO_VERSION = "no_catalog_version"
REASON_VERSION_MISMATCH = "catalog_version_mismatch"
REASON_TABLE_UNAVAILABLE = "table_unavailable"


class IntentDomainTableError(ValueError):
    """业务域表读不了或格式不对。调用方按「不收窄」处理, 不猜。"""


@dataclass(frozen=True)
class DomainTable:
    catalog_version: str
    domains: FrozenSet[str]
    primary: Mapping[str, str]
    secondary: Mapping[str, str]
    keywords: Mapping[str, Sequence[str]]
    rows: Sequence[Mapping[str, Any]] = field(default_factory=tuple)

    def domain_of(self, code: str) -> Optional[str]:
        return self.primary.get(code)

    def secondary_of(self, code: str) -> Optional[str]:
        return self.secondary.get(code)


@dataclass(frozen=True)
class DomainScope:
    """一次缩小的结论。``rows`` 是给提示词的候选; ``narrowed`` 为 False 时 rows 就是原样的 visible。"""
    rows: List[Dict[str, Any]]
    domains: FrozenSet[str]
    narrowed: bool
    reason: str
    catalog_version: Optional[str]


def _parse(raw: Mapping[str, Any]) -> DomainTable:
    if not isinstance(raw, Mapping) or raw.get("schema_version") != 1:
        raise IntentDomainTableError("intent domain table schema_version must be 1")
    version = str(raw.get("catalog_version") or "").strip()
    if not version:
        raise IntentDomainTableError("catalog_version missing")
    domains: Dict[str, List[str]] = {}
    for d in raw.get("domains") or []:
        code = str(d.get("code") or "").strip()
        if not code:
            raise IntentDomainTableError("domain without code")
        domains[code] = [str(k) for k in (d.get("keywords") or []) if str(k).strip()]
    if not ALWAYS_CANDIDATE_DOMAINS <= set(domains):
        raise IntentDomainTableError("table must declare SYSTEM and ANALYTICS")
    primary: Dict[str, str] = {}
    secondary: Dict[str, str] = {}
    rows = list(raw.get("intents") or [])
    for r in rows:
        code = str(r.get("intent_code") or "").strip()
        dom = str(r.get("domain") or "").strip()
        if not code or dom not in domains:
            raise IntentDomainTableError(f"bad row: {code!r} domain={dom!r}")
        primary[code] = dom
        sec = r.get("secondary_domain")
        if sec is not None:
            if sec not in domains or sec == dom:
                raise IntentDomainTableError(f"bad secondary_domain for {code}: {sec!r}")
            secondary[code] = sec
    if not primary:
        raise IntentDomainTableError("table has no intents")
    return DomainTable(catalog_version=version, domains=frozenset(domains), primary=primary,
                       secondary=secondary, keywords={k: tuple(v) for k, v in domains.items()},
                       rows=tuple(rows))


def load_table(path: Optional[Path] = None) -> DomainTable:
    target = Path(path) if path is not None else PACKAGED_TABLE_PATH
    try:
        raw = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, ValueError) as ex:
        raise IntentDomainTableError(f"cannot read {target}: {ex}") from ex
    return _parse(raw)


@lru_cache(maxsize=1)
def get_table() -> DomainTable:
    return load_table()


def _norm(text: str) -> str:
    return unicodedata.normalize("NFKC", text or "").lower()


def detect_domains(query: str, table: DomainTable) -> FrozenSet[str]:
    """这句话提到了哪些业务域 (按表里的业务词, 子串命中, 可多个)。不判 SYSTEM / ANALYTICS: 它们永远在候选里。"""
    q = _norm(query)
    if not q:
        return frozenset()
    hit = set()
    for dom, words in table.keywords.items():
        if dom in ALWAYS_CANDIDATE_DOMAINS:
            continue
        if any(_norm(w) in q for w in words):
            hit.add(dom)
    return frozenset(hit)


def keeps(code: str, domains: Iterable[str], table: DomainTable) -> bool:
    """这个意图在「这句话属于 domains」时是否留在候选里。"""
    primary = table.domain_of(code)
    if primary is None:
        return True  # 工厂自建 / 表外意图: 不当成任何域, 留给语义召回兜底
    secondary = table.secondary_of(code)
    if primary in ALWAYS_CANDIDATE_DOMAINS or secondary in ALWAYS_CANDIDATE_DOMAINS:
        return True  # 跨域通用入口 (次域是系统 / 经营分析的, 如业务单跨域关联, 同样跨域)
    ds = set(domains)
    return primary in ds or secondary in ds


def scope(query: str, visible: Sequence[Mapping[str, Any]], table: Optional[DomainTable],
          requested_version: Optional[str], contextual: bool = False) -> DomainScope:
    """按业务域缩小给决策模型提示词的候选。任何拿不准的情况都原样返回 visible (宁可放宽)。"""
    rows = [dict(r) if not isinstance(r, dict) else r for r in visible]

    def wide(reason: str, domains: FrozenSet[str] = frozenset()) -> DomainScope:
        return DomainScope(rows=rows, domains=domains, narrowed=False, reason=reason,
                           catalog_version=table.catalog_version if table is not None else None)

    if table is None:
        return wide(REASON_TABLE_UNAVAILABLE)
    if not requested_version:
        return wide(REASON_NO_VERSION)
    if requested_version != table.catalog_version:
        return wide(REASON_VERSION_MISMATCH)
    domains = detect_domains(query, table)
    if contextual:
        # 带上下文的轮次 (省略句 / 改参数 / 接着上一个任务): 只看这一句会把上文的域丢掉
        return wide(REASON_CONTEXTUAL, domains)
    if not domains:
        return wide(REASON_NO_DOMAIN)
    if len(domains) > MAX_DETECTED_DOMAINS:
        return wide(REASON_TOO_BROAD, domains)
    kept = [r for r in rows if keeps(str(r.get("intent_code") or ""), domains, table)]
    if not kept:
        return wide(REASON_NO_DOMAIN, domains)
    return DomainScope(rows=kept, domains=domains, narrowed=True, reason=REASON_NARROWED,
                       catalog_version=table.catalog_version)


__all__ = [
    "ALWAYS_CANDIDATE_DOMAINS", "MAX_DETECTED_DOMAINS", "DomainScope", "DomainTable", "IntentDomainTableError",
    "JAVA_TABLE_PATH", "PACKAGED_TABLE_PATH", "detect_domains", "get_table", "keeps", "load_table", "scope",
]
