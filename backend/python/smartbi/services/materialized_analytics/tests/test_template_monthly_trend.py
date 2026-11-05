from smartbi.services.materialized_analytics.compute.polars_backend import PolarsBackend
from smartbi.services.materialized_analytics.schema import DataSchema, Domain, Field, FieldRole
from smartbi.services.materialized_analytics.templates.monthly_trend import MonthlyTrend

def test_factory_monthly_trend():
    schema = DataSchema(upload_id=1, factory_id="F001", domain=Domain.PRODUCTION, fields=(Field("date", FieldRole.TIME, "datetime"), Field("amount", FieldRole.MEASURE, "float")), row_count=3, primary_measure="amount", time_field="date")
    result = MonthlyTrend().run(PolarsBackend.from_rows([{"date": "2026-01-01", "amount": 100.0}, {"date": "2026-01-02", "amount": 200.0}, {"date": "2026-01-03", "amount": 50.0}]), schema)
    assert result.applies and result.data["freq"] == "D" and result.kpis["peak_value"] == 200.0

def test_monthly_trend_single_period_is_honest():
    schema = DataSchema(upload_id=1, factory_id="F001", domain=Domain.UNKNOWN, fields=(Field("date", FieldRole.TIME, "datetime"), Field("amount", FieldRole.MEASURE, "float")), row_count=1, primary_measure="amount", time_field="date")
    result = MonthlyTrend().run(PolarsBackend.from_rows([{"date": "2026-01-01", "amount": 1000.0}]), schema)
    assert result.applies and "周期过少" in result.insight_text

def test_monthly_trend_skips_without_date_field():
    schema = DataSchema(upload_id=1, factory_id="F001", domain=Domain.PRODUCTION, fields=(Field("amount", FieldRole.MEASURE, "float"),), row_count=1, primary_measure="amount", time_field=None)
    result = MonthlyTrend().run(PolarsBackend.from_rows([{"amount": 100.0}]), schema)
    assert not result.applies
