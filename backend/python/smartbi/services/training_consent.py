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
  * Never used, for any factory, authorized or not. Privacy: no setting controls
    these (Steve 2026-11-04):
      - pay / attendance (``PERSONNEL_MARKERS``) WITH a concrete value, in Q&A and
        in utterances, same rule as the process words (Steve 2026-11-03 Q2, A:
        "本月工资总额" alone passes, "本月工资总额 35 万" is blocked);
      - a person or customer of THAT factory, matched exactly against its own
        ``users.full_name`` / ``customers.name`` (``directory``, loaded by
        ``smartbi.services.process_secret_names``). The old regex guess
        (role word + surname, company suffix) is gone: it blocked "员工周报在哪看",
        "我们公司客户有哪些" and similar sentences that name nobody.
  * Not used while the sample factory's training filter (item d, ``TRAINING_FILTER``)
    is on, authorized or not. Secrecy: d is per factory, by the factory that owns the
    sample, default OFF; a read error or an unknown factory counts as ON:
      - a process word WITH a concrete value (Steve 2026-10-29, option A): a
        process-parameter / recipe word (``PARAMETER_MARKERS``) — and in Q&A
        also a step / problem-handling word (``STRUCTURE_MARKERS``,
        ``ADJUSTMENT_MARKERS``) — in the same sentence / record as a concrete
        number (``process_word_with_value``). The word alone ("杀菌温度一般设多少",
        "建议优化卤制工序") no longer blocks: the secret is the value / the
        factory's own step order ("腌制 24 小时再滚揉", "滚揉 … 良品率 92.86%");
      - the factory's protected product terms (``FactoryLists.terms``).
    What crosses factories does not follow d: the shared export and capture of any sample
    that will be masked keep both checks (``process_secret_names.cannot_be_shared``), and the
    finetune gate, whose output is one model for every factory, keeps the whole rule and reads
    no switch. So d only changes what a factory keeps raw for its own use.
    A hit drops the whole sample; masking is no substitute (商业秘密保护规定
    第五至七、十一至十二条: protection is not limited to numbers).

Q&A detection (Steve chose "B"): the input — question plus the data the model
was given — is checked against every marker; the model's answer is checked
against the specific ones only (generic 工艺 / 工序 in advice are ignored). The
answer can only reveal process details from data it was given, and that input
is fully checked.

The markers are a marked proxy: a false hit only drops a sample. Java
(``TrainingSampleRules``) implements the utterance rule identically; both run
``training_sample_rule_cases.json`` and ``process_secret_filter_rule_cases.json``
and a test compares the lists.

Readers: distillation_capture, scripts/export_distillation_dataset.py,
scripts/corpus_judge / corpus_eval_freeze / corpus_g3_sample (``usable_rows_sql``),
scripts/training_filter_replay.py (offline acceptance replay),
scripts/finetune/training_consent_gate.py (loads this file by path, so keep it
free of package-relative imports; it needs backend/python on sys.path for common.*).

Scopes (CHECK constraint in migration V20261001_01):
  none         default; no row means 'none'. Desensitized samples only.
  private      raw data, only for that factory's own model.
  shared_anon  anonymized data may enter the cross-tenant shared model.
Synthetic seed corpus (factory ids SEED_R / SEED_F) belongs to no tenant.
"""
from __future__ import annotations

import re
from functools import lru_cache
from typing import Any, Dict, FrozenSet, Iterable, Mapping, Optional, Tuple

CONSENT_SCOPES = ("none", "private", "shared_anon")
DEFAULT_SCOPE = "none"
# Not a stored consent_scope: what a factory reads as once ``withdrawn_at`` is set
# (training clause 10, migration V20261101_17). A withdrawn factory leaves
# training entirely: nothing captured, exported, judged or sampled.
WITHDRAWN = "withdrawn"

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
RULE_VERSION = "2026-11-03"
# Every real rule version a capture has ever stamped. A row counts as "passed the
# rule" only if its RULE_KEY value is one of these — the key being present is not
# enough (a drill row carries training_rule = "drill").
RULE_VERSIONS = frozenset({"2026-10-24", RULE_VERSION})
DESENSITIZED_KEY = "desensitized"
# Set at capture when the factory's lists (process-secret terms, people,
# customers) could not be read and the LAST SUCCESSFUL lists were used instead:
# the row is held (excluded by usable_rows_sql) until re-judged against the
# current lists (``smartbi.services.process_secret_names.rejudge_held_rows``).
RECHECK_KEY = "secrecy_recheck"
# Set by the re-judgement when a held row still names something on the current
# lists (Steve 2026-11-03 Q5, A): the row is kept for manual review, never deleted,
# and never used (usable_rows_sql / passes_training_rule exclude it).
REJECTED_KEY = "secrecy_rejected"

# Tenants whose rows never enter any training / labeling set: restaurant tenants
# (餐饮废弃 2026-10-08; factories.type RESTAURANT / BRANCH on prod: MOCK_REST, and
# the demo tenant DEMO_REST) and test / backup tenants (F006BK: read-only backup
# of F006; QATEST_*: acceptance test factories). The corpus tables live in the
# SmartBI database and cannot join cretas factories, so these are constants —
# ``tests/test_training_rule_tenants.py`` pins them; add new ones here.
NON_TRAINING_FACTORY_IDS = frozenset({"MOCK_REST", "DEMO_REST", "F006BK"})
NON_TRAINING_FACTORY_PREFIXES = ("QATEST_",)
# business_type the capture stamps on restaurant rows (chat.py _derive_business_type,
# orchestrator insights): a row in this bucket is restaurant data whatever its factory.
NON_TRAINING_BUSINESS_TYPES = frozenset({"restaurant"})

# asyncpg placeholder style; the finetune scripts use SELECT_ALL_SQL only
# (no parameters, so it runs unchanged on psycopg2).
_EFFECTIVE_SCOPE = f"CASE WHEN withdrawn_at IS NOT NULL THEN '{WITHDRAWN}' ELSE consent_scope END"
SELECT_SCOPE_SQL = f"SELECT {_EFFECTIVE_SCOPE} FROM training_consent WHERE factory_id = $1"
SELECT_ALL_SQL = f"SELECT factory_id, {_EFFECTIVE_SCOPE} AS consent_scope FROM training_consent"

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

# Process words: blocked only together with a concrete value in the same record.
QA_INPUT_PROCESS_MARKERS = PARAMETER_MARKERS + STRUCTURE_MARKERS + ADJUSTMENT_MARKERS
# Generic words ignored in the MODEL ANSWER only (measured 2026-10-24: "工艺" /
# "工序" in advice like "建议优化工艺" hit ~200 of 272 un-authorized answers).
GENERIC_ANSWER_WORDS = ("工艺", "工序")
QA_ANSWER_PROCESS_MARKERS = tuple(m for m in QA_INPUT_PROCESS_MARKERS if m not in GENERIC_ANSWER_WORDS)
UTTERANCE_MARKERS = PARAMETER_MARKERS

# Personal identifiers, replaced in every desensitized text (Q&A and utterances).
from common.sample_text_rules import (  # noqa: F401  与 common.llm_egress_text 共用, 与 Java 逐字对齐
    _ID_CARD_RE,
    _BANK_CARD_RE,
    _PHONE_RE,
    _HAN,
    _ORG_SUFFIX,
    _ORG_NAME_RE,
    _SURNAMES,
    _PERSON_ROLE,
    _PERSON_RE,
)

# ---- "a word with a concrete value" (Steve 2026-10-29 option A; 2026-11-03 Q2/Q3; review B1/N1) ----
# The algorithm (records, 12-character window, Chinese numerals, units, tables,
# heading + list) lives in common.training_values, identical to Java
# TrainingValueRules; the shared case file runs both sides.
from common.training_values import (  # noqa: E402
    CONDITIONAL_MARKERS,
    WINDOW,  # noqa: F401
    has_word_with_value,
    mask_values,
)


def process_word_with_value(text: Optional[str], markers: Tuple[str, ...]) -> bool:
    """A process marker with a concrete value nearby (conditional markers 温度 / 时间 /
    压力 / 转速 / 浓度 count when a value + unit follows)."""
    return has_word_with_value(text, markers, CONDITIONAL_MARKERS)


def pay_word_with_value(*texts: Optional[str]) -> bool:
    """A pay / attendance word with a concrete value nearby. Privacy, like person and customer
    names (Steve 2026-11-04): the training filter switch (item d) must not control it."""
    return any(has_word_with_value(t, PERSONNEL_MARKERS) for t in texts)


def _hit(text: Optional[str], markers: Tuple[str, ...]) -> bool:
    return bool(text) and any(m in text for m in markers)


def _names_directory(directory: Optional[Iterable[str]]) -> Iterable[str]:
    if directory is None:
        # Required on purpose: a caller that forgets the factory's people /
        # customers would silently lose the check (形态 B). Pass () explicitly
        # when the sample has no factory to look up.
        raise TypeError("directory (the factory's people / customer names) is required")
    return directory


def qa_process_value(input_text: Optional[str], answer: Optional[str]) -> bool:
    """A process word with a value in a Q&A sample. Follows the training filter (item d)."""
    return (process_word_with_value(input_text, QA_INPUT_PROCESS_MARKERS)
            or process_word_with_value(answer, QA_ANSWER_PROCESS_MARKERS))


def qa_has_blocked_content(input_text: Optional[str], answer: Optional[str], *,
                           directory: Iterable[str], training_filter_on: bool = True) -> bool:
    """True when a Q&A / insight sample must not be stored.

    ``directory``: the sample factory's people and customer names
    (``process_secret_names.FactoryLists.directory``). ``training_filter_on``: item d of the
    factory that owns the sample (``FactoryLists.training_filter_on``). It gates the process
    word rule only: the pay rule and ``directory`` run either way."""
    from common.process_secret_terms import mentions_any

    names = _names_directory(directory)
    if training_filter_on and qa_process_value(input_text, answer):
        return True
    # Q2 (Steve 2026-11-03, A): pay / attendance words block only with a value nearby, too
    if pay_word_with_value(input_text, answer):
        return True
    return mentions_any(names, input_text, answer)


def utterance_has_blocked_content(text: Optional[str], *, directory: Iterable[str]) -> bool:
    """True when an intent utterance must not be stored (any factory): a recipe /
    parameter word or a pay / attendance word with a value, or one of the factory's
    people / customers. The full rule, with no item d: its one production reader is the finetune
    gate, whose output is a model shared by every factory (``scripts/finetune/training_consent_gate.py``).
    Own-factory learning follows d in ``ai.learning.learning_rules.judge``."""
    from common.process_secret_terms import mentions_any

    names = _names_directory(directory)
    return (process_word_with_value(text, UTTERANCE_MARKERS) or pay_word_with_value(text)
            or mentions_any(names, text))


def remove_personal_identifiers(text: Optional[str]) -> Optional[str]:
    """Phone / ID-card / bank-card numbers -> placeholders."""
    if not text:
        return text
    text = _ID_CARD_RE.sub("<证件号>", text)
    text = _BANK_CARD_RE.sub("<卡号>", text)
    return _PHONE_RE.sub("<电话>", text)


def mask_numbers(text: Optional[str]) -> Optional[str]:
    """Desensitize a short utterance: identifiers removed, every value -> ``<数值>``
    (Arabic numbers, numbers glued after a unit such as pH4, Chinese-numeral values)."""
    if not text:
        return text
    return mask_values(remove_personal_identifiers(text) or "")


VALUE_TOKEN = "<数值>"     # what mask_numbers writes in place of a value


@lru_cache(maxsize=8)
def masked_terms(terms: FrozenSet[str]) -> Tuple[str, ...]:
    """Terms as a masked text holds them: 老卤牛肉2号 is 老卤牛肉<数值>号 there. A term that masking
    leaves almost nothing of has no such form: it would match any number."""
    masked = (mask_numbers(t) or "" for t in terms)
    return tuple(m for m in masked if VALUE_TOKEN in m and len(m.replace(VALUE_TOKEN, "")) >= 2)


def keeps_raw(scope: str) -> bool:
    """May a sample of a factory with this scope be stored without desensitization?"""
    return scope in CORPUS_CAPTURE_SCOPES


def is_withdrawn(scope: str) -> bool:
    return scope == WITHDRAWN


def desensitize_utterance(scope: str, text: Optional[str], *,
                          directory: Iterable[str]) -> Tuple[bool, Optional[str]]:
    """Intent-mapping rule: ``(keep, text)``. Withdrawn factories, parameter /
    recipe or pay / attendance content with a value and the factory's people /
    customers are dropped; authorized -> raw; otherwise numbers masked."""
    if is_withdrawn(scope) or utterance_has_blocked_content(text, directory=directory):
        return False, None
    if keeps_raw(scope):
        return True, text
    return True, mask_numbers(text)


# Every factory's people + customers (cretas DB, no parameters): the finetune
# gate (scripts/finetune/training_consent_gate.py, psycopg2) reads it through
# this module so the SQL stays in one place.
from common.process_secret_sql import ALL_FACTORY_DIRECTORY_SQL, ALL_PROTECTED_TERMS_SQL  # noqa: E402,F401
from common.process_secret_terms import mentions_any  # noqa: E402,F401  (finetune gate)


def lists_by_factory(product_rows: Iterable[Mapping[str, Any]],
                     directory_rows: Iterable[Any]) -> Dict[str, Tuple[FrozenSet[str], FrozenSet[str]]]:
    """``{factory_id: (secret_terms, directory)}`` from rows of ALL_PROTECTED_TERMS_SQL /
    ALL_FACTORY_DIRECTORY_SQL (the finetune gate's psycopg2 path)."""
    from common.process_secret_terms import secret_terms_from_policy_rows

    products: Dict[str, list] = {}
    for p in product_rows:
        products.setdefault(p["factory_id"], []).append(p)
    names = directory_by_factory(directory_rows)
    return {f: (secret_terms_from_policy_rows(products.get(f, ())), names.get(f, frozenset()))
            for f in set(products) | set(names)}


def directory_by_factory(rows: Iterable[Any]) -> Dict[str, FrozenSet[str]]:
    """``{factory_id: names}`` from ``(factory_id, name)`` rows of
    ``ALL_FACTORY_DIRECTORY_SQL`` (tuples or mappings)."""
    from common.process_secret_terms import directory_names

    raw: Dict[str, list] = {}
    for r in rows:
        factory_id, name = (r["factory_id"], r["name"]) if isinstance(r, Mapping) else (r[0], r[1])
        if factory_id:
            raw.setdefault(factory_id, []).append(name)
    return {f: directory_names(names) for f, names in raw.items()}


def is_training_tenant(factory_id: Optional[str]) -> bool:
    """A real factory tenant whose rows may be trained on: not a restaurant /
    test / backup tenant (``NON_TRAINING_FACTORY_IDS`` / ``_PREFIXES``) and not
    empty. Synthetic seed tenants are handled separately (``is_synthetic_factory``)."""
    if not factory_id:
        return False
    return (factory_id not in NON_TRAINING_FACTORY_IDS
            and not any(factory_id.startswith(p) for p in NON_TRAINING_FACTORY_PREFIXES))


def has_real_rule_version(metadata: Optional[Mapping[str, Any]]) -> bool:
    """The row was stamped by a real capture rule version (not just "key present")."""
    return bool(metadata) and metadata.get(RULE_KEY) in RULE_VERSIONS


def _sql_list(values: Iterable[str]) -> str:
    return ", ".join("'" + v.replace("'", "''") + "'" for v in sorted(values))


def usable_rows_sql(column: str = "smart_bi_distillation_samples.factory_id",
                    metadata: str = "smart_bi_distillation_samples.metadata",
                    business_type: str = "smart_bi_distillation_samples.business_type") -> str:
    """SQL predicate for corpus scripts (judge, eval freeze, review sampling):
    synthetic seed rows, and real rows that

      * carry a REAL rule version in ``metadata.training_rule`` (one of
        ``RULE_VERSIONS`` — a drill row stamped ``"drill"`` stays out);
      * belong to a factory tenant (no restaurant / test / backup tenant, no
        restaurant bucket, and a factory at all — a row without one cannot be
        matched against any factory's lists);
      * are not held for re-judgement (``RECHECK_KEY``);
      * belong to a factory that has not withdrawn.

    Rows captured before 2026-10-24 stay out until a backfill re-checks them.
    Built only from constants, never from input."""
    synthetic = _sql_list(SYNTHETIC_FACTORY_IDS)
    versions = _sql_list(RULE_VERSIONS)
    excluded = _sql_list(NON_TRAINING_FACTORY_IDS)
    prefixes = " AND ".join(f"{column} NOT LIKE '{p}%'" for p in NON_TRAINING_FACTORY_PREFIXES)
    buckets = _sql_list(NON_TRAINING_BUSINESS_TYPES)
    real = (f"({metadata} ->> '{RULE_KEY}') IN ({versions})"
            f" AND {column} IS NOT NULL AND {column} <> ''"
            f" AND {column} NOT IN ({excluded}) AND {prefixes}"
            f" AND COALESCE({business_type}, '') NOT IN ({buckets})"
            f" AND NOT ({metadata} ? '{RECHECK_KEY}')"
            f" AND NOT ({metadata} ? '{REJECTED_KEY}')")
    # The outer column MUST be table-qualified: an unqualified factory_id inside the
    # subquery would bind to training_consent itself and exclude every row as
    # soon as any factory withdrew.
    not_withdrawn = (f"NOT EXISTS (SELECT 1 FROM training_consent tw "
                     f"WHERE tw.factory_id = {column} AND tw.withdrawn_at IS NOT NULL)")
    return f"(({column} IN ({synthetic}) OR ({real})) AND {not_withdrawn})"


def normalize_scope(raw: Any) -> str:
    """Map a stored value to a known scope. Anything unexpected is 'none'."""
    if isinstance(raw, str) and (raw in CONSENT_SCOPES or raw == WITHDRAWN):
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
