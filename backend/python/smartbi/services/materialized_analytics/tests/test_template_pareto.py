from smartbi.services.materialized_analytics.compute.polars_backend import PolarsBackend
from smartbi.services.materialized_analytics.schema import DataSchema, Domain, Field, FieldRole
from smartbi.services.materialized_analytics.templates.pareto_analysis import ParetoAnalysis

def test_factory_pareto():
    rows = [{"line": "big1", "amount": 400.0}, {"line": "big2", "amount": 400.0}] + [{"line": f"s{i}", "amount": 25.0} for i in range(8)]
    schema = DataSchema(upload_id=1, factory_id="F001", domain=Domain.PRODUCTION, fields=(Field("line", FieldRole.DIMENSION, "string"), Field("amount", FieldRole.MEASURE, "float")), row_count=10, primary_measure="amount")
    result = ParetoAnalysis().run(PolarsBackend.from_rows(rows), schema)
    assert result.applies and result.kpis["labels_for_80pct"] == 2

def test_pareto_skips_few_labels():
    rows = [{"line": "a", "amount": 100.0}, {"line": "b", "amount": 50.0}]
    schema = DataSchema(upload_id=1, factory_id="F001", domain=Domain.UNKNOWN, fields=(Field("line", FieldRole.DIMENSION, "string"), Field("amount", FieldRole.MEASURE, "float")), row_count=2, primary_measure="amount")
    assert not ParetoAnalysis().run(PolarsBackend.from_rows(rows), schema).applies
