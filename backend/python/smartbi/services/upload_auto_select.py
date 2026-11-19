"""「全部数据」时按问题选数据表。

味美 11-19 (B3): 问数据 (AI) 没选数据源时, 后端取「行数最多」的那份上传
(味美是机台停机表 177 行), 问产量、出成率、卷膜都落到机台表上。

这里按问题和每份上传的列名 / 表名的中文二字词重合度打分, 选最贴题的一份;
一份都不沾边 (得分 0) 才退回原来的「行数最多」。只读, 只看当前工厂。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterable, List, Optional, Sequence, Set, Tuple

_CJK_RUN = re.compile(r"[一-鿿]+")

# 问题里的口语说法 → 表里常见的列名用词。只补词, 不删词。
_EXPANSIONS: Tuple[Tuple[Tuple[str, ...], Tuple[str, ...]], ...] = (
    (("产量", "产出", "生产了多少", "产了多少"), ("良品",)),
    (("多少批", "几批", "批数"), ("批次",)),
    (("哪台机", "哪台设备", "设备"), ("机台",)),
    # 口径 A (Steve 11-19): 原表计划只排品项、没有数量, 「计划产量」按计划品项数答
    (("计划产量", "计划量", "计划数"), ("计划品项", "品项数")),
    # 味美 11-19 复测: 「有哪些计划外生产」要列出具体行, 明细在 计划vs实产 表 (结论=计划外生产);
    # 按日表只有「计划外生产品项数」一个计数, 列不出来。只在「哪些/列出」说法下加, 问天数/个数不受影响。
    (("有哪些计划外", "哪些计划外", "列出计划外", "计划外有哪些", "计划外的有哪些", "计划外都有"),
     ("实产", "结论", "产品编码", "计划内")),
    (("哪些产品是计划外", "哪些产品计划外", "哪几个计划外"), ("实产", "结论", "产品编码", "计划内")),
    (("没按计划", "没有生产", "未生产", "没生产", "没完成"), ("实产", "结论")),
)

# 出现在几乎所有表里、不区分表的词
_STOP_BIGRAMS = frozenset({"日期", "月份", "多少", "哪些", "哪个", "哪天", "是多", "一共"})


def _bigrams(text: str) -> Set[str]:
    out: Set[str] = set()
    for run in _CJK_RUN.findall(text or ""):
        for i in range(len(run) - 1):
            bg = run[i:i + 2]
            if bg not in _STOP_BIGRAMS:
                out.add(bg)
    return out


# 问题提到机台/开机/分钟这类设备口径时, 「计划…实际」问的是开机分钟, 不是计划品项
_MACHINE_WORDS = re.compile(r"开机|停机|机台|分钟|时长|小时|设备|哪台")


def _context_expansions(q: str) -> List[str]:
    """需要看整句才能定的扩展词 (单个触发词会误伤别的题)。味美 11-19 复测:

    · 「计划与实际差多少」: 「计划」「实际」正好是机台表的 计划开机/实际开机 列名, 抢走了选表;
      没提开机/分钟/机台时, 口径 A 是计划品项表 (75 个品项产出 55)。
    """
    extra: List[str] = []
    if "计划" in q and re.search(r"实际|实产", q) and not _MACHINE_WORDS.search(q):
        extra += ["计划品项", "品项数", "达成率"]
    return extra


def question_bigrams(question: str) -> Set[str]:
    q = question or ""
    extra = []
    for triggers, adds in _EXPANSIONS:
        if any(t in q for t in triggers):
            extra.extend(adds)
    extra.extend(_context_expansions(q))
    return _bigrams(q + " " + " ".join(extra))


@dataclass
class UploadCandidate:
    upload_id: int
    sheet_name: str = ""
    file_name: str = ""
    row_count: int = 0
    field_names: List[str] = field(default_factory=list)

    @property
    def label(self) -> str:
        return self.sheet_name or self.file_name or f"上传#{self.upload_id}"


def _asks_weekly(q: str) -> bool:
    return bool(re.search(r"哪一?周|按周|每周|周度|第几周", q))


def _asks_daily(q: str) -> bool:
    return bool(re.search(r"哪一?天|按日|每天|每日|哪日", q))


def score_candidate(question: str, cand: UploadCandidate) -> Tuple[int, int, int]:
    """返回排序键 (主分, 逐列命中和, 行数), 越大越贴题。"""
    qb = question_bigrams(question)
    if not qb:
        return (0, 0, cand.row_count or 0)
    field_hits: Set[str] = set()
    per_field = 0
    for name in cand.field_names:
        hit = qb & _bigrams(name)
        field_hits |= hit
        per_field += len(hit)
    title_hits = qb & (_bigrams(cand.sheet_name) | _bigrams(cand.file_name))
    distinct = len(field_hits | title_hits)
    if distinct == 0:
        return (0, 0, cand.row_count or 0)
    names = {n.strip() for n in cand.field_names}
    grain = 0
    if _asks_weekly(question) and "周" in names and "日期" not in names:
        grain += 3
    if _asks_daily(question) and "日期" in names:
        grain += 1
    main = distinct * 3 + len(title_hits) * 2 + grain
    return (main, per_field, cand.row_count or 0)


def choose_upload(question: str, candidates: Sequence[UploadCandidate]) -> Optional[UploadCandidate]:
    """选最贴题的一份; 全部 0 分返回 None (调用方退回原逻辑)。"""
    best: Optional[UploadCandidate] = None
    best_key: Tuple[int, int, int] = (0, 0, 0)
    for cand in candidates:
        key = score_candidate(question, cand)
        if key[0] <= 0:
            continue
        if best is None or key > best_key:
            best, best_key = cand, key
    return best


async def load_candidates(conn, factory_id: str) -> List[UploadCandidate]:
    rows = await conn.fetch(
        """
        SELECT u.id, u.sheet_name, u.file_name, u.row_count,
               COALESCE(array_agg(fd.original_name ORDER BY fd.display_order)
                        FILTER (WHERE fd.original_name IS NOT NULL), '{}') AS field_names
        FROM smart_bi_pg_excel_uploads u
        LEFT JOIN smart_bi_pg_field_definitions fd ON fd.upload_id = u.id
        WHERE u.factory_id = $1
          AND u.upload_status = 'COMPLETED'
          AND u.row_count > 0
        GROUP BY u.id, u.sheet_name, u.file_name, u.row_count
        """,
        factory_id,
    )
    return [
        UploadCandidate(
            upload_id=int(r["id"]),
            sheet_name=r["sheet_name"] or "",
            file_name=r["file_name"] or "",
            row_count=int(r["row_count"] or 0),
            field_names=list(r["field_names"] or []),
        )
        for r in rows
    ]


async def pick_upload_for_question(conn, factory_id: str, question: str) -> Optional[UploadCandidate]:
    if not factory_id or not (question or "").strip():
        return None
    return choose_upload(question, await load_candidates(conn, factory_id))


def auto_select_notice(cand: UploadCandidate) -> str:
    return f"（未指定数据源，本题按问题自动选用数据表「{cand.label}」；不对的话请在右上角换数据源。）\n\n"


__all__: Iterable[str] = (
    "UploadCandidate", "question_bigrams", "score_candidate", "choose_upload",
    "load_candidates", "pick_upload_for_question", "auto_select_notice",
)
