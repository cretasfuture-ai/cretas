"""澄清按钮点选 -> 精确表 PENDING 提议 (「从点选里学」的晋升任务)。

输入: ``ai_intent_selection_log`` (用户在澄清按钮里替系统选了意图 = 带正确答案的样本)。
输出: ``ai_intent_phrase_registry`` 里的 **PENDING** 条目 (source='SELECTION'), 等人审核。

⛔ 本任务永远不产出 APPROVED。owner 规矩: 向量 / 关键词 / LLM / 点选统计都**不授权执行**,
晋升只负责「提议」, 生效必须人工审核 (第①层只读 status='APPROVED')。

规则 (全是具名常量, 改阈值要同步改测试):
  按 (factory_id, normalized_text) 聚合, 一组要同时满足
    * 总点选数           >= MIN_PICKS          (3)
    * 点了「最多的那个意图」的**不同用户数** >= MIN_DISTINCT_USERS (2)
    * 最多的那个意图占比 >= MIN_TOP_SHARE      (0.8)
  「不同用户数」只数点了 top 意图的人, 不数整组: 否则 A 点 X 五次、B 点 Y 一次,
  整组有 2 个用户, 却只有 A 一个人支持 X —— 那是一个人的习惯, 不是「多人共识」。
  user_id 为空的点选计入点选数, 但不计入用户数 (没法证明是另一个人)。

跳过 / 不动:
  * 注册表里已有同键 (factory_id, business_type, normalized_text) 的任何状态
    (APPROVED / REJECTED / PENDING, 任何 source 含 HUMAN) —— 一律不新建、不改状态、
    不覆盖 intent_code。REJECTED 因此永不被重新提议, HUMAN 永不被覆盖。
  * 唯一例外: 已有 PENDING + source='SELECTION' + **同一意图**, 且新计数更大 ->
    只刷新 observed_count / updated_at, 不碰 status。别的 source 的 PENDING (例如老师模型提的)
    不刷新: 它们的 observed_count 语义不同, 混写会让审核的人读错。
  * 工厂在 ``factories`` 表里查不到 / type 不认识 -> 跳过并计数 (⛔ 不默认成 FACTORY:
    把「不认识」翻译成一个合法值, 提议会落在错的业态下)。

business_type: 与 Java ``BusinessDomainUtils.resolveDomain(FactoryType)`` 逐一对齐
(RESTAURANT/BRANCH -> RESTAURANT, LOGISTICS -> LOGISTICS, FACTORY/HEADQUARTERS/CENTRAL_KITCHEN -> FACTORY),
因为运行时第①层按 Java 传来的 businessType 查表 —— 键必须是同一个。

observed_count = 点了 top 意图的次数; sample_text = 该组出现最多的原始问句 (并列取字典序最小)。
写入用 ``ON CONFLICT (factory_id, business_type, normalized_text) DO NOTHING``, 状态写死
在 SQL 字面量里 (不是参数), 唯一约束由库保证, 不靠 check-then-insert。

怎么跑 (手动, **没有 cron / systemd timer**: 现在没有真实流量, 没人读的定时任务比没有更糟):

    cd backend/python
    # 默认 dry-run: 只读, 打印读数与提议, 一行都不写
    PYTHONPATH=.:smartbi python -m ai.jobs.promote_selections
    # 看过 dry-run 再落库 (只会写 PENDING)
    PYTHONPATH=.:smartbi python -m ai.jobs.promote_selections --apply
    # 不走 settings.food_kb_db_url 时显式给库 (注册表 / 点选日志 / factories 都在 cretas 库)
    PYTHONPATH=.:smartbi python -m ai.jobs.promote_selections --dsn postgresql://...

退出码 (三态): 0 = 量到了 (含「点选日志为空」, 这是合法状态, 读数里会写明分母);
              2 = 没量到 (连不上库 / 表不存在), 读数作废。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from collections import Counter
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Mapping, Optional, Tuple

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------- 规则常量
MIN_PICKS = 3
MIN_DISTINCT_USERS = 2
MIN_TOP_SHARE = 0.8
SOURCE = "SELECTION"

# 与 Java BusinessDomainUtils.resolveDomain 对齐。表外的 type -> None -> 跳过 (见模块 docstring)。
FACTORY_TYPE_TO_BUSINESS_TYPE: Dict[str, str] = {
    "FACTORY": "FACTORY",
    "HEADQUARTERS": "FACTORY",
    "CENTRAL_KITCHEN": "FACTORY",
    "RESTAURANT": "RESTAURANT",
    "BRANCH": "RESTAURANT",
    "LOGISTICS": "LOGISTICS",
}

# ---------------------------------------------------------------- SQL
SELECTIONS_SQL = """
SELECT factory_id, user_id, query_text, normalized_text, chosen_intent
FROM ai_intent_selection_log
WHERE normalized_text IS NOT NULL AND normalized_text <> ''
"""

FACTORY_TYPES_SQL = "SELECT id, type FROM factories WHERE id = ANY($1::text[])"

EXISTING_SQL = """
SELECT factory_id, business_type, normalized_text, intent_code, status, source, observed_count
FROM ai_intent_phrase_registry
WHERE factory_id = ANY($1::text[])
"""

# status / source 是 SQL 字面量, 不是参数: 这个任务写 APPROVED 在结构上就不可能。
INSERT_SQL = """
INSERT INTO ai_intent_phrase_registry
    (factory_id, business_type, normalized_text, intent_code, status, source, sample_text, observed_count)
VALUES ($1, $2, $3, $4, 'PENDING', 'SELECTION', $5, $6)
ON CONFLICT (factory_id, business_type, normalized_text) DO NOTHING
"""

# 守卫全写在 WHERE 里: 只动自己提的 PENDING、同一意图、且只往大了改。status 不在 SET 里。
REFRESH_COUNT_SQL = """
UPDATE ai_intent_phrase_registry
SET observed_count = $5, updated_at = NOW()
WHERE factory_id = $1 AND business_type = $2 AND normalized_text = $3
  AND status = 'PENDING' AND source = 'SELECTION' AND intent_code = $4
  AND observed_count < $5
"""


# ---------------------------------------------------------------- 数据结构
Key = Tuple[str, str, str]  # (factory_id, business_type, normalized_text)


@dataclass(frozen=True)
class Proposal:
    factory_id: str
    business_type: str
    normalized_text: str
    intent_code: str
    sample_text: str
    observed_count: int  # 点了 intent_code 的次数
    distinct_users: int  # 点了 intent_code 的不同用户数
    total_picks: int     # 整组点选数
    share: float


@dataclass(frozen=True)
class CountRefresh:
    factory_id: str
    business_type: str
    normalized_text: str
    intent_code: str
    observed_count: int


@dataclass
class PromotionReport:
    log_rows: int = 0
    groups: int = 0
    below_min_picks: int = 0
    below_min_users: int = 0
    below_top_share: int = 0
    unknown_factory: int = 0
    existing_skipped: Counter = field(default_factory=Counter)  # "<status>/<source>[/other-intent]" -> n
    proposals: List[Proposal] = field(default_factory=list)
    refreshes: List[CountRefresh] = field(default_factory=list)


# ---------------------------------------------------------------- 纯函数
def business_type_for(factory_type: Optional[str]) -> Optional[str]:
    return FACTORY_TYPE_TO_BUSINESS_TYPE.get(str(factory_type or "").strip().upper())


def compute_promotions(
    selection_rows: Iterable[Mapping[str, Any]],
    factory_types: Mapping[str, str],
    existing: Mapping[Key, Mapping[str, Any]],
) -> PromotionReport:
    """纯函数: 点选行 + 工厂类型 + 现有注册表 -> 提议 / 计数刷新 / 各类跳过的计数。

    selection_rows: {factory_id, user_id, query_text, normalized_text, chosen_intent}
    factory_types:  {factory_id: factories.type}
    existing:       {(factory_id, business_type, normalized_text): {intent_code, status, source, observed_count}}
    """
    rep = PromotionReport()
    groups: Dict[Tuple[str, str], List[Mapping[str, Any]]] = {}
    for r in selection_rows:
        rep.log_rows += 1
        fid = str(r.get("factory_id") or "")
        key = str(r.get("normalized_text") or "")
        if not fid or not key or not str(r.get("chosen_intent") or ""):
            continue
        groups.setdefault((fid, key), []).append(r)
    rep.groups = len(groups)

    for (fid, norm), rows in sorted(groups.items()):
        total = len(rows)
        if total < MIN_PICKS:
            rep.below_min_picks += 1
            continue
        by_intent = Counter(str(r["chosen_intent"]) for r in rows)
        # 并列取意图码字典序最小, 保证确定性 (并列时占比 <= 0.5, 过不了占比闸)
        top_intent, top_count = sorted(by_intent.items(), key=lambda kv: (-kv[1], kv[0]))[0]
        top_rows = [r for r in rows if str(r["chosen_intent"]) == top_intent]
        users = {str(r["user_id"]) for r in top_rows if r.get("user_id") not in (None, "")}
        if len(users) < MIN_DISTINCT_USERS:
            rep.below_min_users += 1
            continue
        share = top_count / total
        if share < MIN_TOP_SHARE:
            rep.below_top_share += 1
            continue

        btype = business_type_for(factory_types.get(fid))
        if btype is None:
            rep.unknown_factory += 1
            continue

        ex = existing.get((fid, btype, norm))
        if ex is not None:
            same_intent = str(ex.get("intent_code")) == top_intent
            if (same_intent and str(ex.get("status")) == "PENDING" and str(ex.get("source")) == SOURCE
                    and top_count > int(ex.get("observed_count") or 0)):
                rep.refreshes.append(CountRefresh(fid, btype, norm, top_intent, top_count))
            else:
                tag = "%s/%s%s" % (ex.get("status"), ex.get("source"), "" if same_intent else "/other-intent")
                rep.existing_skipped[tag] += 1
            continue

        samples = Counter(str(r.get("query_text") or "") for r in top_rows)
        sample = sorted(samples.items(), key=lambda kv: (-kv[1], kv[0]))[0][0][:500]
        rep.proposals.append(Proposal(fid, btype, norm, top_intent, sample, top_count, len(users), total, share))
    return rep


# ---------------------------------------------------------------- IO
async def run(pool, apply: bool = False) -> PromotionReport:
    """读 -> 算 -> (apply 时) 写。dry-run 不调用任何 execute。"""
    async with pool.acquire() as conn:
        sel = [dict(r) for r in await conn.fetch(SELECTIONS_SQL)]
        factory_ids = sorted({str(r["factory_id"]) for r in sel if r.get("factory_id")})
        ftypes: Dict[str, str] = {}
        existing: Dict[Key, Dict[str, Any]] = {}
        if factory_ids:
            for r in await conn.fetch(FACTORY_TYPES_SQL, factory_ids):
                ftypes[str(r["id"])] = str(r["type"])
            for r in await conn.fetch(EXISTING_SQL, factory_ids):
                existing[(str(r["factory_id"]), str(r["business_type"]).upper(), str(r["normalized_text"]))] = dict(r)
        rep = compute_promotions(sel, ftypes, existing)
        if apply:
            async with conn.transaction():
                for p in rep.proposals:
                    await conn.execute(INSERT_SQL, p.factory_id, p.business_type, p.normalized_text,
                                       p.intent_code, p.sample_text, p.observed_count)
                for u in rep.refreshes:
                    await conn.execute(REFRESH_COUNT_SQL, u.factory_id, u.business_type, u.normalized_text,
                                       u.intent_code, u.observed_count)
    return rep


def format_report(rep: PromotionReport, applied: bool) -> str:
    lines = [
        "mode: %s" % ("APPLY (writes PENDING only)" if applied else "DRY-RUN (nothing written)"),
        "selection log rows read: %d, grouped into %d (factory, normalized_text) keys" % (rep.log_rows, rep.groups),
        "thresholds: picks>=%d, distinct users (on top intent)>=%d, top share>=%.2f"
        % (MIN_PICKS, MIN_DISTINCT_USERS, MIN_TOP_SHARE),
        "rejected by: picks=%d users=%d share=%d unknown_factory=%d; existing registry key skipped=%s"
        % (rep.below_min_picks, rep.below_min_users, rep.below_top_share, rep.unknown_factory,
           dict(rep.existing_skipped) or "{}"),
        "new PENDING proposals: %d; observed_count refreshes: %d" % (len(rep.proposals), len(rep.refreshes)),
    ]
    for p in rep.proposals:
        lines.append("  + %s/%s %r -> %s  picks=%d/%d users=%d share=%.2f sample=%r" % (
            p.factory_id, p.business_type, p.normalized_text, p.intent_code,
            p.observed_count, p.total_picks, p.distinct_users, p.share, p.sample_text))
    for u in rep.refreshes:
        lines.append("  ~ %s/%s %r -> %s  observed_count=%d" % (
            u.factory_id, u.business_type, u.normalized_text, u.intent_code, u.observed_count))
    if rep.log_rows == 0:
        lines.append("(selection log is empty: nothing to promote, not an error)")
    return "\n".join(lines)


async def _open_pool(dsn: Optional[str]):
    if dsn:
        import asyncpg
        return await asyncpg.create_pool(dsn, min_size=1, max_size=2, command_timeout=30)
    from smartbi.config import get_cretas_pool  # 与 main.py 加载注册表用的是同一个池
    return await get_cretas_pool()


async def _amain(args: argparse.Namespace) -> int:
    try:
        pool = await _open_pool(args.dsn)
    except Exception as ex:  # noqa: BLE001
        print("INSTRUMENT DEAD: cannot open pool: %s: %s; nothing was measured" % (type(ex).__name__, ex))
        return 2
    if pool is None:
        print("INSTRUMENT DEAD: no cretas pool (food_kb_db_url unset and no --dsn); nothing was measured")
        return 2
    try:
        rep = await run(pool, apply=args.apply)
    except Exception as ex:  # noqa: BLE001
        print("INSTRUMENT DEAD: %s: %s; nothing was measured" % (type(ex).__name__, ex))
        return 2
    finally:
        await pool.close()
    print(format_report(rep, applied=args.apply))
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="点选日志 -> 精确表 PENDING 提议 (默认 dry-run)")
    ap.add_argument("--apply", action="store_true", help="真写库 (只写 PENDING); 缺省只打印")
    ap.add_argument("--dsn", default=None, help="cretas 库 DSN; 缺省用 settings.food_kb_db_url")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO)
    return asyncio.run(_amain(args))


if __name__ == "__main__":
    sys.exit(main())
