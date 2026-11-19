"""PROTO-YDWM-B1/B2: 跑真实的 excel_async._async_worker_impl（假 DB + 真 xlsx）。

B1 味美 04 醒发蒸制表：温度/分钟/架次/小料kg 是数值列，语义映射没认出（没有 field_def），
   整张表没有指标 -> 保存 422。修后这些列要生成 field_def 且 is_measure=True。
B2 没有任何指标列的上传要标 FAILED（不是 COMPLETED），否则留在数据源下拉里还会成默认数据源。
"""
from __future__ import annotations

from unittest.mock import MagicMock

import pandas as pd

from smartbi.api import excel_async
from smartbi.services.semantic_mapper import (
    FieldMapping,
    SemanticMapper,
    SemanticMappingResult,
)


class _Upload:
    def __init__(self):
        self.statuses = []
        self._status = "PENDING"
        self.file_name = "04醒发蒸制.xlsx"
        self.error_message = None
        self.row_count = None
        self.column_count = None

    @property
    def upload_status(self):
        return self._status

    @upload_status.setter
    def upload_status(self, v):
        self._status = v
        self.statuses.append(v)


class _Query:
    def __init__(self, db):
        self.db = db

    def filter_by(self, **kw):
        return self

    def first(self):
        return self.db.upload

    def delete(self):
        return 0

    def order_by(self, *a, **k):
        return self

    def all(self):
        return []


class _Db:
    def __init__(self):
        self.upload = _Upload()
        self.field_defs = []

    def query(self, *models):
        return _Query(self)

    def bulk_save_objects(self, rows):
        self.field_defs.extend(rows)

    def commit(self):
        pass

    def rollback(self):
        pass

    def close(self):
        pass

    def connection(self):
        return MagicMock()

    def add(self, *_):
        pass


def _run(monkeypatch, tmp_path, frame: pd.DataFrame, mapped: list[FieldMapping]):
    path = tmp_path / "t.xlsx"
    frame.to_excel(path, index=False)
    db = _Db()
    monkeypatch.setattr(excel_async, "SessionLocal", lambda: db)

    async def fake_map_fields(self, columns, sample_data=None, **kw):
        # 模拟真实映射器：只认出 mapped 里的列，其余进 unmapped（不产生 FieldMapping）
        r = SemanticMappingResult()
        r.field_mappings = [m for m in mapped if m.original in columns]
        r.unmapped_fields = [c for c in columns if c not in {m.original for m in mapped}]
        return r

    monkeypatch.setattr(SemanticMapper, "map_fields", fake_map_fields)
    import psycopg2.extras
    monkeypatch.setattr(psycopg2.extras, "execute_values", lambda *a, **k: None)

    import asyncio
    asyncio.run(excel_async._async_worker_impl(
        upload_id=1, tmp_path=str(path), factory_id="F_TEST", sheet_index=None,
        max_rows=1000, selected_region_start=None, selected_region_end=None,
    ))
    return db


_TIME_MAP = FieldMapping(original="日期", standard="time_period", confidence=0.9,
                         method="regex_priority", category="time", data_type="DATE")
_PRODUCT_MAP = FieldMapping(original="产品", standard="product", confidence=0.9,
                            method="rule", category="category", data_type="TEXT")


def test_B1_未映射的数值列生成指标_field_def(monkeypatch, tmp_path):
    frame = pd.DataFrame({
        "日期": ["2026-10-01", "2026-10-02"],
        "产品": ["A", "B"],
        "面团温度": [25.5, 26.0],
        "醒发时间(分钟)": [40, 45],
        "架次": [3, 4],
        "小料kg": [1.5, 2.5],
    })
    db = _run(monkeypatch, tmp_path, frame, [_TIME_MAP, _PRODUCT_MAP])
    measures = {r.original_name for r in db.field_defs if r.is_measure}
    assert measures == {"面团温度", "醒发时间(分钟)", "架次", "小料kg"}, db.field_defs
    assert "COMPLETED" in db.upload.statuses
    assert db.upload.statuses[-1] != "FAILED"


def test_B2_没有指标列的上传标FAILED_不是COMPLETED(monkeypatch, tmp_path):
    frame = pd.DataFrame({
        "日期": ["2026-10-01", "2026-10-02"],
        "产品": ["A", "B"],
        "备注": ["x", "y"],
    })
    db = _run(monkeypatch, tmp_path, frame, [_TIME_MAP, _PRODUCT_MAP])
    assert not any(r.is_measure for r in db.field_defs)
    assert db.upload.statuses[-1] == "FAILED"
    assert "COMPLETED" not in db.upload.statuses
    assert "数值指标" in (db.upload.error_message or "")
