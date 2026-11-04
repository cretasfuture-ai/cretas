"""The read-only post-deploy counter: person / customer names and pay / attendance numbers in
stored Java training rows."""
from __future__ import annotations

import importlib
import json
import os
import uuid

import pytest

counter = importlib.import_module("scripts.training_name_leak_count")


def test_self_test_passes_and_exits_zero(capsys):
    assert counter.self_test() == []
    assert counter.main(["--self-test"]) == 0
    assert json.loads(capsys.readouterr().out) == {"self_test": "ok"}


def test_self_test_exits_two_when_the_matcher_finds_nothing(monkeypatch, capsys):
    monkeypatch.setattr(counter.tc, "mentions_any", lambda *_a, **_k: False)
    assert counter.main(["--self-test"]) == 2
    assert "FAILED" in capsys.readouterr().out


def test_self_test_exits_two_when_the_matcher_hits_everything(monkeypatch):
    monkeypatch.setattr(counter.tc, "mentions_any", lambda *_a, **_k: True)
    assert counter.main(["--self-test"]) == 2


def test_self_test_exits_two_when_the_pay_rule_finds_nothing(monkeypatch, capsys):
    monkeypatch.setattr(counter.tc, "pay_word_with_value", lambda *_a: False)
    assert counter.main(["--self-test"]) == 2
    assert "pay hits" in capsys.readouterr().out


def test_self_test_exits_two_when_the_pay_rule_hits_everything(monkeypatch, capsys):
    monkeypatch.setattr(counter.tc, "pay_word_with_value", lambda *_a: True)
    assert counter.main(["--self-test"]) == 2
    assert "pay hits" in capsys.readouterr().out


def test_the_pay_column_is_separate_needs_no_directory_and_keeps_q2():
    directory = counter.tc.directory_by_factory([("F_A", "王五")])
    report = counter.tally("t", [(1, "F_A", ("工资 8000",), ("工资 8000",)),
                                 (2, "F_A", ("王五报工",), ("王五报工",)),
                                 (3, "F_A", ("查一下本月工资",), ("查一下本月工资",)),
                                 (4, "F_C", ("考勤 3 天",), ("考勤 3 天",))], directory)
    by = {e["factory_id"]: e for e in report}
    assert (by["F_A"]["row_ids"], by["F_A"]["pay_row_ids"]) == (["2"], ["1"])
    assert (by["F_A"]["pay_rows_scanned"], by["F_A"]["rows_with_pay_value"]) == (3, 1)
    assert (by["F_C"]["rows_unchecked"], by["F_C"]["pay_row_ids"]) == (1, ["4"])


def test_rows_are_compared_with_their_own_factory_only_and_unknown_factories_are_not_clean():
    directory = counter.tc.directory_by_factory([("F_A", "王五"), ("F_B", "赵六")])
    report = counter.tally("t", [(1, "F_A", ("王五报工",), ()), (2, "F_A", ("赵六报工",), ()),
                                 (3, "F_C", ("王五",), ()), (4, None, ("王五",), ())], directory)
    by = {e["factory_id"]: e for e in report}
    assert (by["F_A"]["rows_scanned"], by["F_A"]["rows_with_name"], by["F_A"]["row_ids"]) == (2, 1, ["1"])
    assert (by["F_C"]["rows_scanned"], by["F_C"]["rows_unchecked"]) == (0, 1)
    assert by[None]["rows_unchecked"] == 1


def _case_texts(node):
    if isinstance(node, dict):
        for key, value in node.items():
            if key in ("text", "input", "answer", "utterance") and isinstance(value, str):
                yield value
            else:
                yield from _case_texts(value)
    elif isinstance(node, list):
        for value in node:
            yield from _case_texts(value)


def test_the_pay_verdict_survives_the_real_mask_on_every_shared_case():
    services = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "smartbi", "services")
    corpus = {"工资卡 6222021234567890123", "考勤联系 13812345678", "工资 证件 110101199001011234", "本月工资三千"}
    for name in ("process_secret_filter_rule_cases.json", "training_sample_rule_cases.json"):
        with open(os.path.join(services, name), encoding="utf-8") as f:
            corpus |= set(_case_texts(json.load(f)))
    raw_hits = {t for t in corpus if counter.tc.pay_word_with_value(t)}
    assert len(corpus) > 200 and len(raw_hits) >= 10                       # not a pass over nothing
    # the bug this guards: the mask itself hides every one of them
    assert not any(counter.tc.pay_word_with_value(counter.tc.mask_numbers(t)) for t in raw_hits)
    round_trip = {t for t in corpus if counter.tc.pay_word_with_value(counter.unmasked(counter.tc.mask_numbers(t)))}
    assert round_trip == raw_hits                                          # no false zero, no false hit


@pytest.mark.parametrize("name, raw", [("六个核桃", "六个核桃今天发了多少"), ("398礼盒", "398礼盒发货"), ("王五", "王五天没来"),
                                       ("一比一超市", "一比一超市的订单")])
def test_a_masked_name_is_found_in_the_text_the_real_mask_produced(name, raw):
    stored = counter.tc.mask_numbers(raw)
    assert stored != raw and not counter.tc.mentions_any({name}, stored)   # invisible to the plain matcher
    report = counter.tally("t", [(1, "F_A", (stored,), (stored,))], counter.tc.directory_by_factory([("F_A", name)]))
    assert (report[0]["row_ids"], report[0]["masked_name_row_ids"]) == (["1"], ["1"])


def test_a_masked_row_without_the_name_stays_clean():
    directory = counter.tc.directory_by_factory([("F_A", "六个核桃"), ("F_A", "万达超市"), ("F_A", "三十")])
    report = counter.tally("t", [(1, "F_A", ("今天入库 <数值> 箱",), ()), (2, "F_A", ("<数值>个橙子",), ()),
                                 (3, "F_A", ("六个核桃",), ())], directory)
    assert report[0]["row_ids"] == ["3"]                                   # 3 is a plain hit, not a masked one
    assert (report[0]["masked_name_row_ids"], report[0]["names_lost_when_masked"]) == ([], 1)


def test_a_numeral_name_does_not_match_a_row_without_the_token():
    pattern, _ = counter.masked_name_regex({"abc3"})
    assert not counter.names_masked_form(pattern, ("xabc<数值>",))         # same ASCII boundary as mentions_any
    assert counter.names_masked_form(pattern, ("ABC<数值>",))              # folded, like mentions_any
    assert not counter.names_masked_form(counter.masked_name_regex({"王五"})[0], ("王五", None))


def test_the_production_directory_sql_and_matcher_are_the_ones_used():
    assert counter.tc.ALL_FACTORY_DIRECTORY_SQL is __import__(
        "common.process_secret_sql", fromlist=["x"]).ALL_FACTORY_DIRECTORY_SQL
    assert counter.tc.mentions_any is __import__("common.process_secret_terms", fromlist=["x"]).mentions_any


def _connection(schema):
    psycopg2 = pytest.importorskip("psycopg2")
    dsn = os.getenv("SMARTBI_PG_DSN")
    if not dsn:
        if os.getenv("CI"):
            pytest.fail("SMARTBI_PG_DSN is required on CI")
        pytest.skip("no local PostgreSQL")
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute(f"CREATE SCHEMA {schema}")
        cur.execute(f"SET search_path TO {schema}")
        cur.execute("""
            CREATE TABLE users (factory_id varchar(50), full_name text);
            CREATE TABLE customers (factory_id varchar(50), name text, contact_name text, contact_person text);
            CREATE TABLE suppliers (factory_id varchar(50), contact_name text, contact_person text);
            CREATE TABLE ai_training_samples (id bigint, factory_id varchar(50), user_input text, created_at timestamp);
            CREATE TABLE active_learning_samples (id bigint, factory_id varchar(50), user_input text,
                normalized_input text, matched_keywords jsonb, top_candidates jsonb, created_at timestamp);
            CREATE TABLE ai_learned_expressions (id varchar(36), factory_id varchar(50), expression text,
                created_at timestamp);
            INSERT INTO users VALUES ('F_A', '王五');
            INSERT INTO customers VALUES ('F_A', '六个核桃', NULL, NULL);
            INSERT INTO customers VALUES ('F_A', '华联超市', NULL, NULL);
            INSERT INTO suppliers VALUES ('F_B', '赵六', NULL);
        """)
    return conn


def _drop(conn, schema):
    conn.rollback()
    conn.set_session(readonly=False, autocommit=True)
    with conn.cursor() as cur:
        cur.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
    conn.close()


def test_measure_on_real_postgres_counts_all_three_tables_and_writes_nothing():
    schema = "leak_" + uuid.uuid4().hex[:10]
    conn = _connection(schema)
    try:
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO ai_training_samples VALUES
                    (1, 'F_A', '王五今天报工', '2026-11-04 13:00'),
                    (2, 'F_A', '今天产量多少', '2026-11-04 13:00'),
                    (3, 'F_A', '王五昨天报工', '2026-11-03 13:00'),
                    (4, 'F_A', '赵六今天报工', '2026-11-04 13:00'),
                    (5, 'F_A', '本月工资 8000 元', '2026-11-04 13:00'),
                    (6, 'F_A', '查一下本月工资', '2026-11-04 13:00'),
                    (10, 'F_B', '工资 8000 元', '2026-11-04 13:00'),
                    (11, 'F_A', '本月工资 <数值> 元', '2026-11-04 13:00'),
                    (12, 'F_A', '<数值>个核桃发了多少', '2026-11-04 13:00'),
                    (13, 'F_A', '今天入库 <数值> 箱', '2026-11-04 13:00');
                INSERT INTO active_learning_samples VALUES
                    (7, 'F_A', '那批还剩多少', NULL, '["华联超市"]', NULL, '2026-11-04 13:00'),
                    (8, 'F_A', '那批还剩多少', NULL, '["考勤 3 天"]', NULL, '2026-11-04 13:00'),
                    (9, 'F_A', '看下考勤', NULL, NULL, '[{"score": 0.81, "name": "考勤 3 天"}]', '2026-11-04 13:00'),
                    (14, 'F_A', '那批还剩多少', NULL, '["工资 <数值>", "<数值>个核桃"]', NULL, '2026-11-04 13:00');
                INSERT INTO ai_learned_expressions VALUES
                    ('e-1', 'F_A', '华联超市的订单', '2026-11-04 13:00'),
                    ('e-2', NULL, '王五', '2026-11-04 13:00'),
                    ('e-3', NULL, '考勤异常 3 人次', '2026-11-04 13:00');
            """)
        code, report = counter.measure(conn, "2026-11-04 12:00:00")
        assert code == 1
        by = {(e["table"], e["factory_id"]): e for e in report["tables"]}
        assert set(by[("ai_training_samples", "F_A")]["row_ids"]) == {"1", "12"}  # 3 is older, 4 names another factory
        assert by[("ai_training_samples", "F_A")]["masked_name_row_ids"] == ["12"]
        assert by[("ai_training_samples", "F_A")]["rows_scanned"] == 8
        assert set(by[("ai_training_samples", "F_A")]["pay_row_ids"]) == {"5", "11"}  # 6 is the word alone (Q2), 13 no word
        assert set(by[("active_learning_samples", "F_A")]["row_ids"]) == {"7", "14"}  # the jsonb column is read too
        assert by[("active_learning_samples", "F_A")]["masked_name_row_ids"] == ["14"]
        assert set(by[("active_learning_samples", "F_A")]["pay_row_ids"]) == {"8", "14"}  # 9: top_candidates is not pay-checked
        assert by[("ai_learned_expressions", "F_A")]["row_ids"] == ["e-1"]
        assert by[("ai_learned_expressions", None)]["rows_unchecked"] == 2
        assert by[("ai_learned_expressions", None)]["pay_row_ids"] == ["e-3"]  # no directory needed
        assert report["rows_with_name"] == 5
        assert report["rows_with_masked_name"] == 2
        assert report["rows_with_pay_value"] == 6
        dump = json.dumps(report, ensure_ascii=False)
        for raw in ("王五", "华联超市", "赵六", "报工", "工资", "考勤", "8000", "核桃", "<数值>"):
            assert raw not in dump, raw                                    # counts and ids only
        # the session the counter used cannot write
        with conn.cursor() as cur:
            with pytest.raises(Exception, match="read-only"):
                cur.execute("INSERT INTO ai_training_samples VALUES (9, 'F_A', 'x', now())")
        conn.rollback()

        only_pay_code, only_pay = counter.measure(conn, "2026-11-04 12:00:00", "F_B")
        assert (only_pay_code, only_pay["rows_with_name"], only_pay["rows_with_pay_value"]) == (1, 0, 1)
        assert counter.measure(conn, "2026-11-05 00:00:00")[0] == 2        # nothing scanned is not a clean 0
    finally:
        _drop(conn, schema)
