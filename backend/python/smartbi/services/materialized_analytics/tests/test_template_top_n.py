from smartbi.services.materialized_analytics.compute.polars_backend import PolarsBackend
from smartbi.services.materialized_analytics.schema import DataSchema, Domain, Field, FieldRole
from smartbi.services.materialized_analytics.templates.top_n_by_dim import TopNByDim

def test_factory_top_n():
    rows = []
    for line, total in [("A", 1000), ("B", 800), ("C", 600), ("D", 400)]:
        rows.extend({"line": line, "amount": total / 2} for _ in range(2))
    schema = DataSchema(upload_id=1, factory_id="F001", domain=Domain.PRODUCTION, fields=(Field("line", FieldRole.DIMENSION, "string"), Field("amount", FieldRole.MEASURE, "float")), row_count=8, primary_measure="amount")
    result = TopNByDim().run(PolarsBackend.from_rows(rows), schema)
    assert result.applies and result.kpis["top_label"] == "A"

def test_top_n_skips_without_measure():
    schema = DataSchema(upload_id=1, factory_id="F001", domain=Domain.UNKNOWN, fields=(Field("line", FieldRole.DIMENSION, "string"),), row_count=4, primary_measure=None)
    assert not TopNByDim().run(PolarsBackend.from_rows([{"line": "A"}]), schema).applies
