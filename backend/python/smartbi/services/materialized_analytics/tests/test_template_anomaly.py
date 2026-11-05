from smartbi.services.materialized_analytics.compute.polars_backend import PolarsBackend
from smartbi.services.materialized_analytics.schema import DataSchema, Domain, Field, FieldRole
from smartbi.services.materialized_analytics.templates.anomaly_detection import AnomalyDetection

def schema(rows=41):
    return DataSchema(upload_id=1, factory_id="F001", domain=Domain.PRODUCTION, fields=(Field("amt", FieldRole.MEASURE, "float"),), row_count=rows, primary_measure="amt")

def test_factory_anomaly_finds_outlier():
    rows = [{"amt": 100.0 + i % 10} for i in range(40)] + [{"amt": 10000.0}]
    result = AnomalyDetection().run(PolarsBackend.from_rows(rows), schema())
    assert result.applies and result.kpis["outlier_count"] >= 1

def test_factory_anomaly_skips_small_sample():
    assert not AnomalyDetection().run(PolarsBackend.from_rows([{"amt": 100.0}]), schema(10)).applies
