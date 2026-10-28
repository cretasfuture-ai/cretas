"""Read verified financial facts, preserving accounting and as-of boundaries."""
from datetime import date
from decimal import Decimal, InvalidOperation

import pandas as pd
from smartbi import config
from .engine import Dataset
from .sources import dates


CASH_SQL = """
SELECT v.id AS voucher_id, v.voucher_date, e.id AS entry_id,
       e.subject_code, e.debit, e.credit
FROM vouchers v JOIN voucher_entries e ON e.voucher_id=v.id AND e.deleted_at IS NULL
WHERE v.factory_id=$1 AND v.deleted_at IS NULL AND v.status IN ('POSTED','REVERSED')
 AND ($2::date IS NULL OR v.voucher_date >= $2) AND ($3::date IS NULL OR v.voucher_date <= $3)
 AND EXISTS (SELECT 1 FROM voucher_entries ce WHERE ce.voucher_id=v.id AND ce.deleted_at IS NULL
             AND ce.subject_code IN ('1001','1002','1012'))
ORDER BY v.voucher_date,v.id,e.id LIMIT 10001
"""
AR_SQL = """
SELECT id AS "单据ID", counterparty_id AS "客户", outstanding_amount AS "未收金额",
       due_date AS "到期日", CURRENT_DATE AS "截至日", payment_status AS "核销状态", currency_code AS "币种",
       amount AS "原始应收金额", settled_amount AS "已核销金额"
FROM ar_ap_transactions
WHERE factory_id=$1 AND deleted_at IS NULL AND transaction_type='AR_INVOICE'
 AND counterparty_type='CUSTOMER' AND approval_status='APPROVED' AND transaction_date <= CURRENT_DATE
 AND payment_status IN ('UNPAID','PARTIALLY_PAID') AND outstanding_amount>0
ORDER BY id LIMIT 10001
"""
HISTORY_SQL = """
SELECT (make_date(p.year,p.month,1)+INTERVAL '1 month'-INTERVAL '1 day')::date AS "日期", s.material_type_id AS "原料", s.unit AS "单位",
       s.closing_qty AS "库存数量"
FROM inventory_ledger_snapshots s
JOIN accounting_periods p ON p.id=s.accounting_period_id AND p.factory_id=s.factory_id AND p.deleted_at IS NULL
WHERE s.factory_id=$1 AND s.deleted_at IS NULL AND s.snapshot_type='PERIOD_CLOSE' AND p.status='CLOSED'
 AND ($2::date IS NULL OR make_date(p.year,p.month,1) >= $2)
 AND ($3::date IS NULL OR make_date(p.year,p.month,1) <= $3)
ORDER BY p.year,p.month,s.material_type_id,s.id LIMIT 10001
"""


def _decimal(value):
    try:
        val=Decimal(str(value))
        return val if val.is_finite() else None
    except (InvalidOperation,ValueError):
        return None


def classify(code):
    """Same account-family meaning as CashFlowService; explicit numeric codes only."""
    main=str(code or '').split('.')[0]
    if not main.isdigit() or len(main)!=4:
        return None
    if main in {'2001','2231','2232','2501','2502','2701'} or main.startswith('4'):
        return '筹资活动'
    if main[:2] in {'15','16','17','18'} or main in {'1101','1131','1132'}:
        return '投资活动'
    return '经营活动'


def cash_rows(rows):
    """Attribute only vouchers with a provable single activity and balanced entries.

    No guessed split across different activities. Signed reversal entries keep
    their sign; internal cash transfers with zero net movement are excluded.
    """
    grouped={}
    for row in rows:
        grouped.setdefault(row['voucher_id'],[]).append(dict(row))
    result=[]
    for entries in grouped.values():
        debit=[_decimal(e['debit']) for e in entries]
        credit=[_decimal(e['credit']) for e in entries]
        if any(v is None for v in debit+credit) or abs(sum(debit)-sum(credit))>Decimal('0.01'):
            raise ValueError('unbalanced or incomplete voucher')
        codes=[str(e['subject_code'] or '') for e in entries]
        cash=[i for i,c in enumerate(codes) if c in {'1001','1002','1012'}]
        amount=sum((debit[i]-credit[i] for i in cash),Decimal(0))
        if amount==0:
            continue
        counterpart=[i for i,c in enumerate(codes) if c not in {'1001','1002','1012'} and debit[i]!=credit[i]]
        activities={classify(codes[i]) for i in counterpart}
        if len(activities)!=1 or None in activities:
            raise ValueError('mixed or unknown cash activity requires explicit allocation')
        result.append({'日期':str(entries[0]['voucher_date']), '现金流活动':activities.pop(),
                       '现金流金额':float(amount), '币种':'CNY'})
    return result


async def load(factory_id,request,can_price):
    start,end=dates(request)
    datasets,errors,gaps=[],[],[]
    specs=[('historical-inventory','已关账期间库存快照',HISTORY_SQL,[factory_id,start,end])]
    if can_price:
        specs.append(('cashflow','已过账现金凭证 · 单一可核对活动分类',CASH_SQL,[factory_id,start,end]))
        if (end is None or end>=date.today()) and (start is None or start<=date.today()):
            specs.append(('receivables',f'应收未核销余额 · 截至{date.today()}',AR_SQL,[factory_id]))
        else:
            gaps.append({'id':'receivables.history','title':'所选期间应收账龄','missing':['所选期间覆盖今天才可使用当前核销余额；历史或未来截至日需独立余额快照']})
    else:
        gaps.append({'id':'finance.permissions','title':'现金流与应收','missing':['金额指标查看权限']})
    try:
        pool=await config.get_cretas_pool()
        if pool is None:
            raise RuntimeError('pool unavailable')
    except Exception:
        return [],[{'source':'finance','message':'财务业务来源读取失败，请重试或联系管理员'}],gaps
    for name,title,sql,params in specs:
        try:
            async with pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute("SELECT set_config('app.factory_id', $1, true)",factory_id)
                    rows=await conn.fetch(sql,*params)
            if len(rows)>10000:
                raise ValueError('source limit exceeded')
            if name=='cashflow':
                rows=cash_rows(rows)
            frame=pd.DataFrame([dict(row) for row in rows])
            if not can_price:
                frame=frame.drop(columns=['期末库存金额'],errors='ignore')
            datasets.append(Dataset(frame,name+' · '+title,currency='CNY'))
        except ValueError:
            gaps.append({'id':name+'.accounting','title':title,'missing':['完整且可对账的来源明细；现金流跨活动分摊、未知科目或超过明细上限须先核对，不静默舍弃']})
        except Exception:
            errors.append({'source':name,'message':'来源读取失败，请重试或联系管理员核对数据服务'})
    gaps.extend([
        {'id':'sales.channel','title':'渠道经营分析','missing':['工厂订单明确的销售渠道；收款方式和客户来源不能代替成交渠道']},
        {'id':'finance.unit-source','title':'业务单元经营分析','missing':['已归属真实部门的收入/成本事实；不按客户或供应商关系猜部门']},
        {'id':'production.oee-source','title':'设备OEE','missing':['设备计划运行、实际运行、理想节拍及同一设备数量/质量分母']},
    ])
    return datasets,errors,gaps
