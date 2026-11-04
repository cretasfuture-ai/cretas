"""训练数据过滤 (item d) 的 Python 侧: 保密产品词和「工艺词 + 数值」按样本所属工厂的 d 走, 人名 / 客户名 /
工资考勤数值永远过滤。

🔴 这次改动的本质是一次转换: d 默认关, 而 Python 以前不读 d、对所有工厂全过滤。上线那一刻, 所有没配过 d 的
工厂从「全过滤」变成「只过滤隐私那部分」。所以矩阵里的 ``default_no_row`` 就是那次转换本身,
``on`` / ``read_error`` / ``unknown_factory`` 是转换之前的行为。

每条都走真实入口 (capture / 私有导出 / 共享导出 / corpus_judge 发给外部模型的内容 / 两个学习器 / 挂起重判)。
假的只有配置表的读取 (``factory_secrecy._read``) 和两个库的连接; 名单、开关、规则都是真的。
微调闸是同步的另一条路, 同一张矩阵在 test_finetune_training_consent_gate。
"""
from __future__ import annotations

import argparse
import importlib
import json

import pytest

from ai.learning import expression_learner as el
from ai.learning import keyword_learner as kl
from ai.learning.learning_rules import Verdict, judge as learning_judge
from common import factory_secrecy as fs
from scripts import export_distillation_dataset as export_mod
from smartbi.services import distillation_capture as dc
from smartbi.services import process_secret_names as psn
from smartbi.services.training_consent import RULE_VERSION
from tests.test_learning_cross_factory import _Pool as _LearnerPool
from tests.test_process_secret_corpus_scripts import _Pool, _judge_pool

F = "F_D"
PRODUCT = "秘制牛肉干"
ANSWER = "增长 8%"

SAMPLES = {
    "plain": "本月产量多少",
    "product": PRODUCT + "本月销量多少",
    "process": "这个产品配方里盐放 3 克",
    "person": "王五本月产量多少",
    "customer": "华联超市的订单多少",
    "pay": "本月工资 8000 元",
}
FILTER_OFF_STATES = ("default_no_row", "explicit_off")
STATES = FILTER_OFF_STATES + ("on", "read_error", "unknown_factory")


def kept(state):
    """What may pass: privacy never, d-items only with the filter off."""
    return {"plain", "product", "process"} if state in FILTER_OFF_STATES else {"plain"}


class _Cretas:
    """The cretas database as the lists read it: one protected product, two names."""

    def __init__(self):
        self.fetches = 0
        self.down = False

    def acquire(self):
        outer = self

        class _C:
            async def fetch(self, sql, *args):
                outer.fetches += 1
                if outer.down:
                    raise RuntimeError("cretas db down")
                if "process_secret_policies" in sql:
                    return [{"factory_id": F, "id": "p1", "code": "C-1", "name": PRODUCT, "category": None,
                             "product_category": None, "aliases": None}]
                return [{"factory_id": F, "name": "王五"}, {"factory_id": F, "name": "华联超市"}]

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

        return _C()


class _World:
    def __init__(self, table):
        self.table = table
        self.cretas = _Cretas()

    def set(self, state):
        self.table.clear()
        if state == "explicit_off":
            self.table[F] = {fs.TRAINING_FILTER: False}
        elif state == "on":
            self.table[F] = {fs.TRAINING_FILTER: True}
        elif state == "read_error":
            self.table["__broken__"] = True
        elif state == "unknown_factory":
            self.table["__missing__"] = (F,)
        else:
            assert state == "default_no_row"
        fs.invalidate()


@pytest.fixture
def world(monkeypatch):
    table: dict = {}

    async def fake_read(factory_id):
        if table.get("__broken__"):
            raise RuntimeError("db down")
        if factory_id in table.get("__missing__", ()):
            return None
        return dict(table.get(factory_id, {}))

    w = _World(table)

    async def cretas_pool(pool):
        return pool or w.cretas

    monkeypatch.setattr(fs, "_read", fake_read)
    monkeypatch.setattr(psn, "_pool_or_default", cretas_pool)
    # conftest stubs these on the modules that imported them: the real lookups run here
    monkeypatch.setattr(dc, "factory_lists_or_last_good", psn.factory_lists_or_last_good)
    monkeypatch.setattr(export_mod, "all_factory_lists", psn.all_factory_lists)
    fs.invalidate()
    psn.clear_cache()
    yield w
    fs.invalidate()
    psn.clear_cache()


def _row(kind, *, row_id=None, factory_id=F, **extra):
    return {"id": row_id if row_id is not None else list(SAMPLES).index(kind), "factory_id": factory_id,
            "source": "chat_qa", "business_type": "factory", "input_text": SAMPLES[kind], "teacher_output": ANSWER,
            "metadata": {"training_rule": RULE_VERSION, "desensitized": True}, **extra}


def _kinds(row_ids):
    names = list(SAMPLES)
    return {names[i] for i in row_ids}


# ------------------------------------------------------------------ capture


class _SmartbiPool:
    def __init__(self, scope):
        self.scope = scope
        self.stored = []

    def acquire(self):
        outer = self

        class _C:
            async def fetchval(self, *_a):
                return outer.scope

            async def execute(self, sql, *args):
                if "INSERT INTO smart_bi_distillation_samples" in sql:
                    outer.stored.append(args)
                    return "INSERT 0 1"
                return "DELETE 0"

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

        return _C()


async def _capture(pool, text, answer=ANSWER):
    await dc.persist_distillation_sample(pool, "chat_qa", "qa", text, answer, business_type="factory", factory_id=F)


@pytest.mark.parametrize("state", STATES)
async def test_capture(world, state):
    world.set(state)
    stored = {}
    for kind, text in SAMPLES.items():
        pool = _SmartbiPool("private")
        await _capture(pool, text)
        if pool.stored:
            stored[kind] = pool.stored[0]
    assert set(stored) == kept(state)
    assert all(args[6] == SAMPLES[k] for k, args in stored.items())       # authorized: stored as is


@pytest.mark.parametrize("answer", ["华联超市的订单最多", "考勤异常 3 人次"])
async def test_privacy_in_the_answer_is_refused_with_the_filter_off(world, answer):
    world.set("default_no_row")
    pool = _SmartbiPool("private")
    await _capture(pool, "本月产量多少", answer)
    assert pool.stored == []


async def test_with_the_filter_off_an_unauthorized_factory_still_stores_masked_numbers(world):
    """d gates what is dropped, not desensitization: consent still decides that."""
    world.set("default_no_row")
    pool = _SmartbiPool("none")
    await _capture(pool, SAMPLES["process"])
    assert len(pool.stored) == 1
    stored = pool.stored[0][6]
    assert "3 克" not in stored and "<数值>" in stored
    assert json.loads(pool.stored[0][11])["desensitized"] is True


async def test_a_failed_lookup_held_on_the_last_good_lists_still_splits_on_d(world):
    world.set("default_no_row")
    await psn.factory_lists(F)                       # last good lists exist
    psn.clear_cache(forget_last_good=False)
    world.cretas.down = True

    product, person = _SmartbiPool("private"), _SmartbiPool("private")
    await _capture(product, SAMPLES["product"])
    await _capture(person, SAMPLES["person"])
    assert len(product.stored) == 1 and json.loads(product.stored[0][11])["secrecy_recheck"] is True
    assert person.stored == []

    # the settings read fails with the lists (same database): filter on, the stale terms apply
    world.set("read_error")
    dropped = _SmartbiPool("private")
    await _capture(dropped, SAMPLES["product"])
    assert dropped.stored == []


# ------------------------------------------------------------------ export


@pytest.mark.parametrize("state", STATES)
async def test_private_export(world, state, tmp_path):
    """Through _export_private: the file a private factory's own model would train on."""
    world.set(state)
    rows = [_row(k) for k in SAMPLES]
    pool = _Pool({"training_consent": [{"factory_id": F, "consent_scope": "private"}],
                  "smart_bi_distillation_samples": rows})
    args = argparse.Namespace(factory=F, out=str(tmp_path), source=None, business_type=None, min_quality=None)
    await export_mod._export_private(args, pool)
    lines = (tmp_path / f"dataset_private_{F}.jsonl").read_text(encoding="utf-8").splitlines()
    assert _kinds(json.loads(line)["meta"]["id"] for line in lines) == kept(state)


@pytest.mark.parametrize("state", STATES)
async def test_the_shared_export_never_takes_a_factorys_own_terms(world, state):
    """Crosses factories, so no switch opens it (Java checkSharedContent): with d off the product
    stays out of the shared set while the same row goes into the factory's own model above."""
    world.set(state)
    gated = export_mod.gate_shared_rows([_row(k) for k in SAMPLES], {}, await psn.all_factory_lists())
    assert _kinds(g["row"]["id"] for g in gated) == kept(state) - {"product"}


async def test_the_shared_export_compares_a_row_with_its_own_factory_only(world):
    """Not a new union of every factory's lists: another factory's product of the same name is not
    secret there, and that row stayed in the shared export before this switch existed."""
    world.set("on")
    other = _row("product", factory_id="F_OTHER", row_id=9)
    gated = export_mod.gate_shared_rows([other], {}, await psn.all_factory_lists())
    assert [g["row"]["id"] for g in gated] == [9]


async def test_a_failed_lookup_still_aborts_the_export_with_the_filter_off(world, tmp_path):
    world.set("default_no_row")
    world.cretas.down = True
    pool = _Pool({"training_consent": [{"factory_id": F, "consent_scope": "private"}],
                  "smart_bi_distillation_samples": [_row("plain")]})
    args = argparse.Namespace(factory=F, out=str(tmp_path), source=None, business_type=None, min_quality=None)
    with pytest.raises(RuntimeError):
        await export_mod._export_private(args, pool)
    assert list(tmp_path.iterdir()) == []


# ------------------------------------------------------------------ corpus_judge


def _judge_rows():
    return [{**_row(k), "metadata": None} for k in SAMPLES]


async def _prompts_of_a_run(judge, monkeypatch, rows):
    pool = _judge_pool(rows=rows)

    async def make_pool(_dsn):
        return pool

    sent = []

    async def chain(_slot, payload, **_k):
        sent.append(payload["messages"][1]["content"])
        return {"choices": [{"message": {"content":
                '{"factuality": 4, "actionability": 4, "fluency": 4, "overall": 4}'}}]}

    monkeypatch.setattr(judge, "_make_pool", make_pool)
    monkeypatch.setattr(judge, "call_chain", chain)
    await judge.run_judge(limit=50)
    return "\n".join(sent), len(sent)


@pytest.mark.parametrize("state", STATES)
async def test_what_reaches_the_outside_judge(world, state, monkeypatch):
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_factory_lists", psn.all_factory_lists)
    world.set(state)
    prompts, runs = await _prompts_of_a_run(judge, monkeypatch, _judge_rows())
    assert runs == len(kept(state))                     # the judge really ran
    assert {k for k, text in SAMPLES.items() if text in prompts} == kept(state)


async def test_switching_the_filter_off_releases_what_the_judge_excluded_and_nothing_private(world, monkeypatch):
    """The transition on rows already flagged: excluded under the filter-on list, they are looked at
    again because the fingerprint moved. The product row is released, the person row is not."""
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_factory_lists", psn.all_factory_lists)
    world.set("on")
    fp_on = psn.terms_fingerprint(await psn.all_factory_lists())

    world.set("default_no_row")
    fp_off = psn.terms_fingerprint(await psn.all_factory_lists())
    assert fp_on != fp_off

    flag = json.dumps({"process_secret_excluded": True, "process_secret_list": fp_on})
    rows = [{**_row("product"), "metadata": flag}, {**_row("person"), "metadata": flag}]
    pool = _judge_pool(rows=rows)
    kept_rows = await judge.fetch_unjudged_rows(pool, 50)
    assert [r["input_text"] for r in kept_rows] == [SAMPLES["product"]]
    unmark = [a for s, a in pool.executed if "metadata - 'process_secret_excluded'" in s]
    mark = [a for s, a in pool.executed if "jsonb_build_object('process_secret_excluded'" in s]
    assert unmark == [([_row("product")["id"]],)]
    assert [m[0] for m in mark] == [[_row("person")["id"]]]


async def test_a_row_kept_while_the_filter_was_off_does_not_go_out_once_it_is_on(world, monkeypatch):
    """New with this switch: capture no longer guarantees a row has no process value, so the
    readers have to check. Before, the judge never looked because capture had already refused it."""
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_factory_lists", psn.all_factory_lists)
    pool = _SmartbiPool("private")
    world.set("default_no_row")
    await _capture(pool, SAMPLES["process"])
    assert len(pool.stored) == 1                         # kept with the raw value

    world.set("on")
    row = _row("process")
    prompts, runs = await _prompts_of_a_run(judge, monkeypatch, [{**row, "metadata": None}, {**_row("plain"), "metadata": None}])
    assert runs == 1 and SAMPLES["process"] not in prompts
    assert export_mod.passes_training_rule(row, await psn.all_factory_lists()) is False


# ------------------------------------------------------------------ learners


async def _none_scope(_factory_id):
    return "none"


@pytest.fixture
def fake_embedding(monkeypatch):
    async def embed(_text):
        return [1.0, 0.0]

    monkeypatch.setattr(el, "get_embedding", embed)
    monkeypatch.setattr(el, "vec_to_pgvector_text", lambda v: "[1,0]")


@pytest.mark.parametrize("state", STATES)
async def test_expression_learner(world, state, fake_embedding):
    """Default lookups: the production wiring, not an injected list."""
    world.set(state)
    pool = _LearnerPool([{"query": text, "intent_code": kind, "factory_id": F, "confidence": 0.97}
                         for kind, text in SAMPLES.items()])
    learner = el.ExpressionLearner(pool, consent_scope=_none_scope)
    assert await learner.run_once() == len(kept(state))
    assert {args[0] for _, args in pool.conn.executed} == kept(state)
    assert len(learner.retry) == 0


@pytest.mark.parametrize("state", STATES)
async def test_keyword_learner(world, state):
    world.set(state)
    pool = _LearnerPool([{"query": text, "intent_code": kind, "factory_id": F, "confidence": 0.95}
                         for kind, text in SAMPLES.items()],
                        intent_rows={(F, kind): "[]" for kind in SAMPLES})
    learner = kl.KeywordLearner(pool, existing_keywords={}, consent_scope=_none_scope)
    learned = await learner.run_once()
    assert {intent for _, intent in learned} == kept(state)


async def test_a_failed_list_lookup_is_retry_with_the_filter_off(world):
    from ai.learning.learning_rules import default_factory_lists

    world.set("default_no_row")
    world.cretas.down = True
    verdict = await learning_judge(F, SAMPLES["plain"], consent_scope=_none_scope, factory_lists=default_factory_lists)
    assert verdict is Verdict.RETRY


# ------------------------------------------------------------------ held rows


@pytest.mark.parametrize("state", STATES)
async def test_held_rows_are_rejudged_by_the_current_switch(world, state):
    world.set(state)
    held = [{"id": list(SAMPLES).index(k), "factory_id": F, "input_text": "产品A", "teacher_output": ANSWER,
             "metadata": json.dumps({"secrecy_recheck": {"raw_input": text, "raw_output": ANSWER}})}
            for k, text in SAMPLES.items()]
    pool = _Pool({"WHERE metadata ? 'secrecy_recheck'": held})
    counts = await psn.rejudge_held_rows(pool, await psn.all_factory_lists())
    assert counts["released"] == len(kept(state))
    rejected = [a for s, a in pool.executed if "jsonb_build_object('secrecy_rejected'" in s]
    assert _kinds(a[0] for a in rejected) == set(SAMPLES) - kept(state)
    reasons = {list(SAMPLES)[a[0]]: json.loads(a[1])["reason"] for a in rejected}
    assert reasons["person"] == "names_factory_list" and reasons["pay"] == "blocked_content"
    assert all(SAMPLES[k] not in a[1] for k in SAMPLES for a in rejected)   # the reason, never the text


# ------------------------------------------------------------------ the switch itself


async def test_the_switch_is_read_fresh_and_not_cached_with_the_lists(world):
    world.set("default_no_row")
    off = await psn.factory_lists(F)
    fetches = world.cretas.fetches
    world.set("on")
    on = await psn.factory_lists(F)
    assert world.cretas.fetches == fetches              # the lists came from their cache
    assert (off.training_filter_on, on.training_filter_on) == (False, True)


async def test_the_terms_themselves_do_not_change_with_any_switch(world):
    """What is built from ``terms`` stays independent of d: only ``filtered_terms`` moves."""
    world.set("default_no_row")
    off = (await psn.all_factory_lists())[F]
    world.set("on")
    on = (await psn.all_factory_lists())[F]
    assert PRODUCT in off.terms and off.terms == on.terms and off.directory == on.directory
    assert off.filtered_terms == frozenset() and on.filtered_terms == on.terms


async def test_lists_built_by_hand_filter_everything():
    lists = psn.lists_from_rows([{"name": PRODUCT}], ["王五"])
    assert lists.training_filter_on is True and lists.names_any(SAMPLES["product"])
    assert psn.EMPTY_LISTS.training_filter_on is True


async def test_the_offline_replay_follows_the_switch_it_is_given(world):
    from scripts import training_filter_replay as replay

    world.set("default_no_row")
    by_factory = await psn.all_factory_lists()
    assert replay.explain_row(_row("product"), by_factory)["reasons"] == []
    assert replay.explain_row(_row("process"), by_factory)["reasons"] == []
    assert replay.explain_row(_row("person"), by_factory)["reasons"] == ["factory_person_or_customer"]
    world.set("on")
    by_factory = await psn.all_factory_lists()
    assert replay.explain_row(_row("product"), by_factory)["reasons"] == ["process_secret_term"]
    assert replay.explain_row(_row("process"), by_factory)["reasons"] == ["process_value_in_input"]
