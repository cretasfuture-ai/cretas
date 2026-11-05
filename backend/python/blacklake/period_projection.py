"""Date cohorts retain full work-order costs; never slice cumulative task wages."""
from copy import deepcopy
from datetime import timezone

from .full_archive import digest
from .sync import timestamp


def instant(value):
    if value in (None, ''):
        return None
    try:
        return timestamp(value, 'Asia/Shanghai')
    except (ValueError, TypeError, RuntimeError, OverflowError):
        return None


def project_period(data, metadata, window):
    """Return a new scoped source view. Dates are inclusive in UI, half-open in UTC."""
    source = deepcopy(data)
    selected, unassigned, unassigned_completed = [], 0, 0
    undated_completed_ids=[]
    undated_ids=[]
    date_field = 'actualEndTime' if window.basis == 'completed' else 'createTime'
    for project in data.get('project', []):
        when = instant(project.get(date_field))
        if when is None:
            unassigned += 1
            undated_ids.append(project['id'])
            unassigned_completed += str(project.get('status')) == '20'
            if str(project.get('status'))=='20':undated_completed_ids.append(project['id'])
        if (when is not None and window.start_at <= when < window.end_at
                and (window.basis != 'completed' or str(project.get('status')) == '20')):
            selected.append(project)
    ids = {str(p['id']) for p in selected}
    codes = {p['projectCode'] for p in selected}
    all_projects = {p['projectCode']: str(p['id']) for p in data.get('project', [])}
    source['project'] = deepcopy(selected)
    source['task'] = [p for p in source.get('task', []) if str(p.get('projectId')) in ids]
    source['output'] = [p for p in source.get('output', []) if str(p.get('projectId')) in ids]
    movements, period_movements, undated_movements = [], [], 0
    for order in data.get('stock_order', []):
        when = instant(order.get('bizAt'))
        if when is None:
            undated_movements += 1
        in_period = when is not None and window.start_at <= when < window.end_at
        if in_period:
            period_movements.append(order)
        # All dates of a selected work-order's materials belong to its lifecycle.
        # Unlinked movements are diagnosed only if their own business date is in range.
        details = [d for d in order.get('orderDetails', [])
                   if str(d.get('associatedBizOrderId')) in ids or d.get('associatedBizOrderCode') in codes
                   or (in_period and (str(d.get('associatedBizOrderType')) != '10'
                       or all_projects.get(d.get('associatedBizOrderCode')) != str(d.get('associatedBizOrderId'))))]
        if details:
            movements.append({**deepcopy(order), 'orderDetails': deepcopy(details)})
    source['stock_order'] = movements
    # Reference definitions are not additive period facts. Keep them for identity checks.
    # Snapshot balances cannot reconstruct historical closing balances.
    snapshot = instant(metadata.get('stock_finished_at',metadata.get('finished_at')))
    snapshot_in_period = snapshot is not None and window.start_at <= snapshot < window.end_at
    if not snapshot_in_period:
        source['stock'] = []
    for resource in ('materials', 'project_expand'):
        source[resource] = [p for p in source.get(resource, []) if str(p.get('projectId')) in ids]
    semantic_period = {k: v for k, v in window.wire().items() if k not in ('kind', 'canonicalRange')}
    fingerprint = digest({'period': semantic_period, 'projectionVersion': 2, 'data': source,
                          'sourceRun': metadata['run_id'], 'dataAsOf': metadata['finished_at'],
                          'inventoryAsOf':metadata.get('stock_finished_at',metadata.get('finished_at'))})
    scoped_metadata = {**metadata, 'run_id': metadata['run_id'] + ':period:' + fingerprint[:20],
                       'stock_pairs_not_returned': metadata.get('stock_pairs_not_returned') if snapshot_in_period else None}
    basis = '实际完工日期' if window.basis == 'completed' else '工单创建日期'
    scope_description = (f'{window.start_date.isoformat()} 至 {window.end_date.isoformat()}（北京时间，含首尾日期），'
                         f'按{basis}选择 {len(selected)} 张工单。材料与人工取这些工单在本次快照中记录的完整生命周期成本，'
                         '包括跨天领退料和后续补录；不是期间实际发生费用，也不是当时已知的历史关账结果。')
    source_checked=instant(metadata.get('finished_at'))
    coverage = ('source_time_unknown' if source_checked is None else 'source_snapshot_before_period_end'
                if source_checked < window.end_at else 'source_completeness_unverified')
    return source, scoped_metadata, {
        **window.wire(), 'fingerprint': fingerprint, 'scopeDescription': scope_description,
        'dataAsOf': metadata['finished_at'], 'baseRunId': metadata['run_id'],
        'unassignedProjects': unassigned, 'unassignedCompletedProjects': unassigned_completed,
        'unassignedCompletedProjectIds':undated_completed_ids,
        'unassignedProjectIds':undated_ids,
        'selectedProjects': len(selected), 'coverageStatus': coverage,
        'inventoryStatus': 'snapshot_within_period' if snapshot_in_period else 'historical_balance_unavailable',
        'inventoryAsOf': metadata.get('stock_finished_at',metadata.get('finished_at')) if snapshot_in_period else None,
        'lastAvailableInventoryAsOf':metadata.get('stock_finished_at',metadata.get('finished_at')),
        'latestCompletedDate':metadata.get('latest_completed_date'),
        'periodMovementOrders': len(period_movements),
        'unassignedMovementOrders': undated_movements,
        'sourceDateField': date_field,
    }


def describe_period(analysis, period):
    analysis['periodMetadata'] = period
    analysis['inventory']['movementOrders'] = period['periodMovementOrders']
    analysis['production']['completionRateApplicable'] = period['basis'] != 'completed'
    if period['basis'] == 'completed':
        analysis['production']['completionRate'] = None
        analysis['production']['cohortStatus'] = 'completed_only'
    analysis['limitations'] = [p for p in analysis['limitations'] if '225' not in p]
    analysis['limitations'].append(period['scopeDescription'])
    if period['unassignedMovementOrders']:
        analysis['limitations'].append(f"全快照有 {period['unassignedMovementOrders']} 张出入库单缺少有效业务日期，不能猜测归入所选期间，期间出入库统计可能不完整。")
    if period['inventoryStatus'] == 'historical_balance_unavailable':
        analysis['limitations'].append('所选范围没有历史库存余额快照，不用当前库存倒推历史余额；缺少历史快照不代表库存为零。')
    for report in analysis['sectionInsights']:
        report['scopeDescription'] = period['scopeDescription'] + ' 仅图表单位、产品搜索不改变这组分析的日期范围。'
        if report['sectionId'] == 'inventory':
            report['scopeDescription'] = (f"{period['startDate']} 至 {period['endDate']}的历史出入库业务日期；"
                                          '库存余额只在本期存在采集快照时展示，未重建历史期末余额。')
            report['observations'][0] = (f"按业务日期归入本期 {period['periodMovementOrders']} 张出入库单；"
                                         f"全快照另有 {period['unassignedMovementOrders']} 张缺少有效业务日期，无法归期。")
            if period['inventoryStatus'] == 'historical_balance_unavailable':
                report['summary'] = '这段时间有出入库单可核对，但没有对应的历史库存余额快照，不能判断当时库存是多少。'
        if report['sectionId'] == 'production' and period['basis'] == 'completed':
            report['summary'] = (f"按实际完工日期选中的 {period['selectedProjects']} 张工单均为已完工状态；"
                                 '这不是全厂同期所有工单的完成率，任务工时仍是这些工单的累计值。')
            report['calculation'][0] = '按实际完工日期筛选时，不计算全厂同期完工率；需要按创建日期选择全部工单，再核对完工状态。'
    return analysis
