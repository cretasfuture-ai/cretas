import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from smartbi.external_benchmarks.collectors import CollectorResult
from smartbi.external_benchmarks.service import ExternalBenchmarkService


@pytest.mark.asyncio
@pytest.mark.parametrize("payload", [{}, {"status": "已完成", "rows": [1, 2]}])
async def test_job_run_encodes_jsonb_and_redacts_url(payload):
    conn = AsyncMock()
    pool = MagicMock()
    pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
    pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)
    result = CollectorResult(source_code="test", status="SUCCESS", raw_payload=payload,
                             request_url="https://example.org/?key=secret-key")
    with patch("smartbi.external_benchmarks.service.get_pg_pool", AsyncMock(return_value=pool)):
        await ExternalBenchmarkService().record_job_run(result)
    args = conn.execute.await_args.args
    assert "$6::jsonb" in args[0]
    assert isinstance(args[6], str)
    assert json.loads(args[6]) == payload
    assert "secret-key" not in args[5]
