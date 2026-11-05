from smartbi.services.materialized_analytics.compute.polars_backend import PolarsBackend
from smartbi.services.materialized_analytics.schema import DataSchema, Domain, Field, FieldRole
from smartbi.services.materialized_analytics.templates.category_distribution import CategoryDistribution

def test_factory_category_distribution():
    rows = [{"line": "A", "amount": 600.0}, {"line": "B", "amount": 300.0}, {"line": "C", "amount": 100.0}]
    schema = DataSchema(upload_id=1, factory_id="F001", domain=Domain.PRODUCTION, fields=(Field("line", FieldRole.DIMENSION, "string"), Field("amount", FieldRole.MEASURE, "float")), row_count=3, primary_measure="amount")
    result = CategoryDistribution().run(PolarsBackend.from_rows(rows), schema)
    assert result.applies and result.kpis["top_share_pct"] == 60.0

def test_category_distribution_rejects_too_many_categories():
    rows = [{"line": f"x{i}", "amount": i} for i in range(20)]
    schema = DataSchema(upload_id=1, factory_id="F001", domain=Domain.UNKNOWN, fields=(Field("line", FieldRole.DIMENSION, "string"), Field("amount", FieldRole.MEASURE, "float")), row_count=20, primary_measure="amount")
    assert not CategoryDistribution().run(PolarsBackend.from_rows(rows), schema).applies
