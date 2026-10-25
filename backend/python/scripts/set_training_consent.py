"""训练授权管理 CLI — upsert 一条 training_consent 行 (数据主权管理工具).

设置某工厂/餐饮租户的训练授权范围 (consent_scope):
  - none         默认; 真实数据不进任何训练集 (default-deny)
  - private      授权单租户私有模型: 原始数据, 仅自己的私有模型
  - shared_anon  授权跨租户共享模型: 脱敏后进共享训练集

授权 / 取消授权 / 撤回 (训练条款第九、十条):
    cd /www/wwwroot/cretas/code/backend/python && source venv-current/bin/activate
    # 授权: 必须注明合同编号 (数据库约束兜底)
    PYTHONPATH=.:smartbi python -m scripts.set_training_consent \
        --factory F001 --scope private --contract HT-2026-001 --by 张三 --note "专属模型"
    # 取消授权 (回到默认: 问答 / 意图脱敏后仍默认参与训练):
    PYTHONPATH=.:smartbi python -m scripts.set_training_consent \
        --factory F001 --scope none --by 张三
    # 客户撤回 (退出训练: 不采集、不导出、不打分), 人工流程见
    # docs/security/training-withdrawal-runbook.md:
    PYTHONPATH=.:smartbi python -m scripts.set_training_consent \
        --factory F001 --withdraw --by 张三 --note "2026-10-25 书面撤回, 工单 W-001"

强制点: export_distillation_dataset.py 导出语料时按本表 consent_scope 门控。
合成语料 (SEED_R/SEED_F) 不受本表管理, 无条件可用。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from typing import Optional

# Path-bootstrap: allow `python scripts/set_training_consent.py` (not only -m)
# by putting backend/python and backend/python/smartbi on sys.path.
_HERE = os.path.dirname(os.path.abspath(__file__))
_BACKEND_PY = os.path.dirname(_HERE)               # backend/python
for _p in (_BACKEND_PY, os.path.join(_BACKEND_PY, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("set_consent")

_VALID_SCOPES = ("none", "private", "shared_anon")

_UPSERT_SQL = """
    INSERT INTO training_consent
        (factory_id, consent_scope, authorized_at, authorized_by, note, contract_no,
         withdrawn_at, withdrawn_by, withdrawal_note, updated_at)
    VALUES
        ($1, $2, $3, $4, $5, $6, NULL, NULL, NULL, NOW())
    ON CONFLICT (factory_id) DO UPDATE SET
        consent_scope   = EXCLUDED.consent_scope,
        authorized_at   = EXCLUDED.authorized_at,
        authorized_by   = EXCLUDED.authorized_by,
        note            = EXCLUDED.note,
        contract_no     = EXCLUDED.contract_no,
        withdrawn_at    = NULL,
        withdrawn_by    = NULL,
        withdrawal_note = NULL,
        updated_at      = NOW()
"""

# Withdrawal: authorization is dropped and the factory leaves training entirely.
_WITHDRAW_SQL = """
    INSERT INTO training_consent
        (factory_id, consent_scope, withdrawn_at, withdrawn_by, withdrawal_note, updated_at)
    VALUES
        ($1, 'none', NOW(), $2, $3, NOW())
    ON CONFLICT (factory_id) DO UPDATE SET
        consent_scope   = 'none',
        authorized_at   = NULL,
        contract_no     = NULL,
        withdrawn_at    = NOW(),
        withdrawn_by    = EXCLUDED.withdrawn_by,
        withdrawal_note = EXCLUDED.withdrawal_note,
        updated_at      = NOW()
"""


def validate(scope: Optional[str], contract: Optional[str], withdraw: bool) -> None:
    """Argument rules (raise ValueError): withdraw XOR scope; authorizing needs a contract."""
    if withdraw == (scope is not None):
        raise ValueError("give exactly one of --scope or --withdraw")
    if scope in ("private", "shared_anon") and not (contract or "").strip():
        raise ValueError("authorizing (private / shared_anon) requires --contract <合同编号>")


async def _run(
    factory: str,
    scope: Optional[str],
    by: Optional[str],
    note: Optional[str],
    dsn: Optional[str],
    contract: Optional[str] = None,
    withdraw: bool = False,
) -> None:
    validate(scope, contract, withdraw)
    if dsn:
        import asyncpg
        pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2)
    else:
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("Postgres pool unavailable (check DB env / --dsn)")

    # 'none' / revoke → clear authorized_at (no longer authorized).
    from datetime import datetime
    authorized_at = None if scope == "none" else datetime.now()

    async with pool.acquire() as conn:
        if withdraw:
            await conn.execute(_WITHDRAW_SQL, factory, by, note)
        else:
            await conn.execute(_UPSERT_SQL, factory, scope, authorized_at, by, note,
                               (contract or "").strip() or None)

    if withdraw:
        logger.info(
            "WITHDRAWN: factory %s left training (no capture / export / judging) by %s%s",
            factory, (by or "<unspecified>"), (f" — {note}" if note else ""),
        )
    elif scope == "none":
        logger.info(
            "REVOKED: factory %s → consent_scope='none' (default-deny; real data "
            "excluded from all training).", factory,
        )
    else:
        logger.info(
            "SET: factory %s → consent_scope=%r contract=%s by %s%s",
            factory, scope, contract, (by or "<unspecified>"),
            (f" — {note}" if note else ""),
        )


def main() -> None:
    ap = argparse.ArgumentParser(description="Upsert a training_consent row (data-sovereignty admin)")
    ap.add_argument("--factory", required=True, help="tenant factory_id")
    ap.add_argument("--scope", choices=_VALID_SCOPES,
                    help="none | private | shared_anon (private / shared_anon need --contract)")
    ap.add_argument("--contract", default=None, help="contract number backing the authorization")
    ap.add_argument("--withdraw", action="store_true",
                    help="customer withdrawal: leave training entirely (clause 10)")
    ap.add_argument("--by", default=None, help="authorizing operator name (audit)")
    ap.add_argument("--note", default=None, help="optional note (contract ref etc.)")
    ap.add_argument("--dsn", default=None, help="optional asyncpg DSN; defaults to app DB env")
    args = ap.parse_args()
    try:
        validate(args.scope, args.contract, args.withdraw)
    except ValueError as e:
        ap.error(str(e))
    asyncio.run(_run(args.factory, args.scope, args.by, args.note, args.dsn,
                     contract=args.contract, withdraw=args.withdraw))


if __name__ == "__main__":
    main()
