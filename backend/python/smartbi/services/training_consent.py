"""Training-data rules: the one place that reads ``training_consent`` and decides
what a factory sample may carry into a training / distillation / labeling set.

Owner rule (Steve, 2026-10-24; replaces the 2026-10-22 "nothing without consent").
Customer wording: docs sent to the website session the same day.

  * Scheduling features (headcount, equipment, durations, efficiency) are used
    by default ONLY for that factory's own scheduling model — never for a
    general model, never merged across factories (training is off today; any
    future trainer must partition by factory_id).
  * AI Q&A samples (question + the data the model saw -> answer) are used by
    default after desensitization (``corpus_anonymize.desensitize_for_training``:
    generic name placeholders, phone / ID / bank numbers removed, EVERY number
    -> <数值> — revenue, cost, margins and other ratios, output, inventory; only
    the question form and the reasoning remain). Factories with ``training_consent`` 'private' /
    'shared_anon' are stored without desensitization.
  * Intent mappings (utterance -> intent) are used by default with numbers and
    phone / ID / bank numbers masked. They teach UNDERSTANDING, so an utterance
    may describe steps ("腌制和滚揉一起做").
  * Never used, for any factory, authorized or not:
      - process parameters / recipe content (``PARAMETER_MARKERS``);
      - in Q&A, a factory's step order / merging / problem handling
        (``STRUCTURE_MARKERS``, ``ADJUSTMENT_MARKERS``) — Q&A can train a model
        that GENERATES advice, and a factory's own order must never be
        suggested to another factory (recommendations only come from a
        reviewed template library);
      - per-person pay / attendance (``PERSONNEL_MARKERS``);
      - in Q&A and in intent utterances, a specific customer name
        (``mentions_specific_customer``) or a person's name (``mentions_person_name``; surnames 于/向/付/万 are left out
        because they are mostly function words, so "于经理" is not caught).
    A hit drops the whole sample; masking is no substitute (商业秘密保护规定
    第五至七、十一至十二条: protection is not limited to numbers).

Q&A detection (Steve chose "B"): the input — question plus the data the model
was given — is checked against every marker; the model's answer is checked
against the specific ones only (generic 工艺 / 工序 in advice are ignored). The
answer can only reveal process details from data it was given, and that input
is fully checked.

The markers are a marked proxy tuned to over-block: a false hit only drops a
sample. Java (``TrainingSampleRules``) implements the utterance rule identically;
both run ``training_sample_rule_cases.json`` and a test compares the lists.

Readers: distillation_capture, scripts/export_distillation_dataset.py,
scripts/corpus_judge / corpus_eval_freeze / corpus_g3_sample (``usable_rows_sql``),
scripts/finetune/training_consent_gate.py (loads this file by path, so keep it
stdlib-only with no package-relative imports).

Scopes (CHECK constraint in migration V20261001_01):
  none         default; no row means 'none'. Desensitized samples only.
  private      raw data, only for that factory's own model.
  shared_anon  anonymized data may enter the cross-tenant shared model.
Synthetic seed corpus (factory ids SEED_R / SEED_F) belongs to no tenant.
"""
from __future__ import annotations

import re
from typing import Any, Dict, Iterable, Mapping, Optional, Tuple

CONSENT_SCOPES = ("none", "private", "shared_anon")
DEFAULT_SCOPE = "none"

# Sentinel tenants of the synthetic seed corpus (seed_chart_insight_corpus.py).
SYNTHETIC_FACTORY_IDS = frozenset({"SEED_R", "SEED_F"})

# Scopes under which a factory's samples are stored without desensitization.
CORPUS_CAPTURE_SCOPES = frozenset({"private", "shared_anon"})

# May a real sample enter a model shared across tenants? Only 'shared_anon',
# and only after anonymization.
SHARED_MODEL_SCOPES = frozenset({"shared_anon"})

# metadata keys stamped at capture: RULE_KEY on every real sample that passed
# this rule; DESENSITIZED_KEY on the ones stored desensitized.
RULE_KEY = "training_rule"
RULE_VERSION = "2026-10-24"
DESENSITIZED_KEY = "desensitized"

# asyncpg placeholder style; the finetune scripts use SELECT_ALL_SQL only
# (no parameters, so it runs unchanged on psycopg2).
SELECT_SCOPE_SQL = "SELECT consent_scope FROM training_consent WHERE factory_id = $1"
SELECT_ALL_SQL = "SELECT factory_id, consent_scope FROM training_consent"

# Keep every tuple below identical to TrainingSampleRules (Java); a test compares.
#
# Recipe / process parameters. Deliberately absent: cost / production terms —
# measured 2026-10-24 on prod, "BOM" hit 397 of 780 samples, almost all the
# "BOM成本" column of cost-variance tables (cost data, used by default).
PARAMETER_MARKERS = (
    "配方", "配比", "配料比", "投料比", "用量", "添加量", "添加比例",
    "工艺参数", "工艺条件", "加工参数", "杀菌温度", "杀菌时间", "灭菌温度", "灭菌时间",
    "中心温度", "油温", "转速", "糖度", "盐度", "含水量", "浓度", "pH",
)
# Process steps / structure (blocked in Q&A, allowed in utterances).
STRUCTURE_MARKERS = (
    "工艺", "工序", "腌制", "蒸煮", "蒸制", "煮制", "发酵", "杀菌", "灭菌", "巴氏", "卤制",
    "烘烤", "油炸", "熬制", "搅拌", "滚揉", "烟熏", "干燥", "速冻", "解冻", "预冷", "炒制",
    "焯水",
)
# How problems are handled (blocked in Q&A, allowed in utterances).
ADJUSTMENT_MARKERS = ("回料", "返工")
# Per-person pay / attendance (blocked in Q&A, allowed in utterances).
PERSONNEL_MARKERS = ("工资", "薪资", "薪酬", "考勤")

QA_INPUT_MARKERS = PARAMETER_MARKERS + STRUCTURE_MARKERS + ADJUSTMENT_MARKERS + PERSONNEL_MARKERS
# Generic words ignored in the MODEL ANSWER only (measured 2026-10-24: "工艺" /
# "工序" in advice like "建议优化工艺" hit ~200 of 272 un-authorized answers).
GENERIC_ANSWER_WORDS = ("工艺", "工序")
QA_ANSWER_MARKERS = tuple(m for m in QA_INPUT_MARKERS if m not in GENERIC_ANSWER_WORDS)
UTTERANCE_MARKERS = PARAMETER_MARKERS

# Personal identifiers, replaced in every desensitized text (Q&A and utterances).
_ID_CARD_RE = re.compile(r"(?<![0-9A-Za-z])\d{17}[\dXx](?![0-9A-Za-z])", re.ASCII)
_BANK_CARD_RE = re.compile(r"(?<![0-9A-Za-z])\d{16,19}(?![0-9A-Za-z])", re.ASCII)
_PHONE_RE = re.compile(r"(?<![0-9A-Za-z])1[3-9]\d{9}(?![0-9A-Za-z])", re.ASCII)
# Numbers in short utterances. Identifiers like F006 / V2 / B12 are kept.
_NUMBER_RE = re.compile(r"(?<![A-Za-z0-9_.<])\d+(?:\.\d+)?", re.ASCII)


# Specific customer names and person names (Steve 2026-10-24): a Q&A sample that
# names one is not stored at all. Marked proxies tuned to over-block.
_HAN = "一-龥"
_ORG_SUFFIX = r"(?:股份有限公司|有限公司|公司|集团|超市|商贸|商行|批发部|门市部|连锁|酒店|饭店|食品厂)"
_ORG_NAME_RE = re.compile(rf"[{_HAN}A-Za-z0-9]{{2,12}}{_ORG_SUFFIX}")
_CUSTOMER_NAMED_RE = re.compile(
    rf"客户(?:名称|名)?\s*[:：]\s*[{_HAN}A-Za-z0-9]{{2,}}"   # 客户：张记 / 客户名称: XX
    rf"|客户\s*[「“\"『][^」”\"』]{{2,}}[」”\"』]"               # 客户「张记」
    rf"|[「“\"『][^」”\"』]{{2,}}[」”\"』]\s*客户"               # 「张记」客户
)
_SURNAMES = (
    "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏"
    "魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵"
    "钱严覃武戴莫孔汤"  # 于 / 向 / 付 / 万 left out: mostly function words / units (测 2026-10-24: "负责人于今日")
)
_PERSON_ROLE = (r"(?:联系人|负责人|经办人|业务员|销售员|司机|操作员|质检员|审核人|审批人|签收人|"
                r"收货人|制单人|姓名|员工|工人|班长|组长|主管|仓管员?)")
_PERSON_RE = re.compile(
    rf"{_PERSON_ROLE}\s*[:：|]?\s*[{_SURNAMES}][{_HAN}]{{1,2}}"                       # 操作员：王五
    rf"|(?<![{_HAN}])[{_SURNAMES}][{_HAN}]?(?:先生|女士|小姐|经理|总监|老板|师傅|厂长)"  # 李经理 / 王老板
    rf"|(?<![{_HAN}])[{_SURNAMES}]总(?![量额数计成产体部结和监经])"                      # 王总
    rf"|(?:联系|找|通知|叫|告诉)(?!方式|方法|方面|电话|人)[{_SURNAMES}][{_HAN}]{{1,2}}(?=[\s，,。；;、:：（(]|$|\d)"  # 联系张三
)


def mentions_specific_customer(text: Optional[str]) -> bool:
    """「客户」together with a specific name (company-like name, 客户：XX, quoted name)."""
    if not text or "客户" not in text:
        return False
    return bool(_CUSTOMER_NAMED_RE.search(text) or _ORG_NAME_RE.search(text))


def mentions_person_name(text: Optional[str]) -> bool:
    """A person's name in a role / title / contact context (张三经理, 操作员：王五)."""
    return bool(text) and bool(_PERSON_RE.search(text))


def _hit(text: Optional[str], markers: Tuple[str, ...]) -> bool:
    return bool(text) and any(m in text for m in markers)


def qa_has_blocked_content(input_text: Optional[str], answer: Optional[str]) -> bool:
    """True when a Q&A / insight sample must not be stored (any factory)."""
    if _hit(input_text, QA_INPUT_MARKERS) or _hit(answer, QA_ANSWER_MARKERS):
        return True
    return any(mentions_specific_customer(t) or mentions_person_name(t)
               for t in (input_text, answer))


def utterance_has_blocked_content(text: Optional[str]) -> bool:
    """True when an intent utterance must not be stored (any factory): recipe /
    parameter content, a specific customer, or a person's name."""
    return (_hit(text, UTTERANCE_MARKERS) or mentions_specific_customer(text)
            or mentions_person_name(text))


def remove_personal_identifiers(text: Optional[str]) -> Optional[str]:
    """Phone / ID-card / bank-card numbers -> placeholders."""
    if not text:
        return text
    text = _ID_CARD_RE.sub("<证件号>", text)
    text = _BANK_CARD_RE.sub("<卡号>", text)
    return _PHONE_RE.sub("<电话>", text)


def mask_numbers(text: Optional[str]) -> Optional[str]:
    """Desensitize a short utterance: identifiers removed, every number -> ``<数值>``."""
    if not text:
        return text
    return _NUMBER_RE.sub("<数值>", remove_personal_identifiers(text) or "")


def keeps_raw(scope: str) -> bool:
    """May a sample of a factory with this scope be stored without desensitization?"""
    return scope in CORPUS_CAPTURE_SCOPES


def desensitize_utterance(scope: str, text: Optional[str]) -> Tuple[bool, Optional[str]]:
    """Intent-mapping rule: ``(keep, text)``. Parameter / recipe content is dropped
    for every factory; authorized -> raw; otherwise numbers and identifiers masked."""
    if utterance_has_blocked_content(text):
        return False, None
    if keeps_raw(scope):
        return True, text
    return True, mask_numbers(text)


def usable_rows_sql(column: str = "factory_id", metadata: str = "metadata") -> str:
    """SQL predicate for corpus scripts (judge, eval freeze, review sampling):
    synthetic seed rows and rows that passed this rule at capture. Rows captured
    before 2026-10-24 stay out until a backfill re-checks them. Built only from
    constants, never from input."""
    synthetic = ", ".join(f"'{f}'" for f in sorted(SYNTHETIC_FACTORY_IDS))
    return f"({column} IN ({synthetic}) OR ({metadata} ? '{RULE_KEY}'))"


def normalize_scope(raw: Any) -> str:
    """Map a stored value to a known scope. Anything unexpected is 'none'."""
    if isinstance(raw, str) and raw in CONSENT_SCOPES:
        return raw
    return DEFAULT_SCOPE


def scope_from_map(consent: Mapping[str, str], factory_id: Optional[str]) -> str:
    """Scope of ``factory_id`` in a pre-loaded map; absent factory -> 'none'."""
    if not factory_id:
        return DEFAULT_SCOPE
    return normalize_scope(consent.get(factory_id))


def is_synthetic_factory(factory_id: Optional[str]) -> bool:
    return (factory_id or "") in SYNTHETIC_FACTORY_IDS


def factories_with_scope(consent: Mapping[str, str], scopes: Iterable[str]) -> list:
    """Sorted factory ids whose scope is in ``scopes``."""
    wanted = frozenset(scopes)
    return sorted(f for f, s in consent.items() if f and normalize_scope(s) in wanted)


async def fetch_consent_scope(conn, factory_id: str) -> str:
    """Consent scope of one factory over an asyncpg-style connection.

    No row -> 'none'. Database errors (table missing, permission, connection)
    are NOT swallowed: the caller decides, and capture treats them as 'none'.
    """
    raw = await conn.fetchval(SELECT_SCOPE_SQL, factory_id)
    return normalize_scope(raw)


async def fetch_consent_map(conn) -> Dict[str, str]:
    """``{factory_id: scope}`` for every row. Errors propagate to the caller."""
    rows = await conn.fetch(SELECT_ALL_SQL)
    return {r["factory_id"]: normalize_scope(r["consent_scope"]) for r in rows}
