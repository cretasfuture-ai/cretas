from decimal import Decimal

import pytest

from .finance_sources import cash_rows, classify, dates


def test_cash_rows_preserves_posted_cash_sign_and_internal_transfer_is_zero():
    rows = [
        {"voucher_id": "r", "voucher_date": "2026-01-02", "subject_code": "1012", "debit": 100, "credit": 0},
        {"voucher_id": "r", "voucher_date": "2026-01-02", "subject_code": "6001", "debit": 0, "credit": 100},
        {"voucher_id": "x", "voucher_date": "2026-01-03", "subject_code": "1002", "debit": 100, "credit": 0},
        {"voucher_id": "x", "voucher_date": "2026-01-03", "subject_code": "1012", "debit": 0, "credit": 100},
    ]
    result = cash_rows(rows)
    assert result == [{"日期": "2026-01-02", "现金流活动": "经营活动", "现金流金额": 100.0, "币种": "CNY"}]


def test_cash_rows_rejects_ambiguous_activity_and_unbalanced_voucher():
    ambiguous = [
        {"voucher_id": "m", "voucher_date": "2026-01-02", "subject_code": "1002", "debit": 100, "credit": 0},
        {"voucher_id": "m", "voucher_date": "2026-01-02", "subject_code": "6001", "debit": 0, "credit": 50},
        {"voucher_id": "m", "voucher_date": "2026-01-02", "subject_code": "1601", "debit": 0, "credit": 50},
    ]
    with pytest.raises(ValueError, match="mixed"):
        cash_rows(ambiguous)

    unbalanced = [
        {"voucher_id": "u", "voucher_date": "2026-01-02", "subject_code": "1002", "debit": 100, "credit": 0},
        {"voucher_id": "u", "voucher_date": "2026-01-02", "subject_code": "6001", "debit": 0, "credit": 99},
    ]
    with pytest.raises(ValueError, match="unbalanced"):
        cash_rows(unbalanced)


def test_activity_classification_matches_account_semantics():
    assert classify("6001") == "经营活动"
    assert classify("1601.01") == "投资活动"
    assert classify("2001") == "筹资活动"
    assert classify("unknown") is None


def test_dates_are_explicit_period_boundaries():
    class Request:
        period_type = "month"
        year = 2026
        start_month = 2
        end_month = 3

    start, end = dates(Request())
    assert str(start) == "2026-02-01"
    assert str(end) == "2026-03-31"

