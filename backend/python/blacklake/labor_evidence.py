"""Production staff are registered identities, not proof of a complete shift roster."""
from collections import defaultdict
from decimal import Decimal, InvalidOperation
import hashlib
import hmac
import json
import secrets

from .period_projection import instant


def person_key(person_id, key, scope):
    if isinstance(person_id,bool) or not str(person_id).isdecimal() or int(person_id)<=0:return None
    wire=json.dumps([*scope,str(int(person_id))],separators=(',',':')).encode()
    return 'worker:'+hmac.new(key,wire,hashlib.sha256).hexdigest()


def local_person_key(directory):
    from .secrets import load_local_secret,save_local_secret
    path=directory/'labor-person-key.dpapi'
    if not path.exists():save_local_secret(path,{'key':secrets.token_hex(32)})
    key=bytes.fromhex(load_local_secret(path)['key'])
    if len(key)!=32:raise ValueError('LABOR_PERSON_KEY_INVALID')
    return key


def number(value):
    if value is None or isinstance(value,bool):return None
    try:
        result=Decimal(str(value))
        return result if result.is_finite() and result>=0 else None
    except (InvalidOperation,ValueError):return None


def evidence(data):
    tasks=data.get('task',[]);outputs=data.get('output',[])
    if not any('sumEffectiveTime' in row for row in tasks) and not any('productionWorkerKey' in row for row in outputs):return {}
    by_task={str(row['id']):row for row in tasks};children=defaultdict(list);orphans=set();seen=set()
    intervals=defaultdict(list);overlapping=set()
    for row in outputs:
        task=by_task.get(str(row.get('taskId')))
        if (not task or str(task.get('projectId'))!=str(row.get('projectId'))
                or str(task.get('processId'))!=str(row.get('processId')) or str(row.get('id')) in seen):
            orphans.add(str(row.get('projectId')));continue
        seen.add(str(row['id']));children[str(task['id'])].append(row)
        start,end=instant(row.get('starttimeofReport')),instant(row.get('endtimeofReport'))
        worker=row.get('productionWorkerKey')
        if worker and start and end and end>start:intervals[worker].append((start,end,str(row['id'])))
    for rows in intervals.values():
        rows.sort()
        for index,(start,end,id) in enumerate(rows):
            for other_start,other_end,other_id in rows[index+1:]:
                if other_start>=end:break
                overlapping.update((id,other_id))
    result={}
    for task in tasks:
        rows=children[str(task['id'])];keys=sorted({r['productionWorkerKey'] for r in rows if r.get('productionWorkerKey')})
        issues=[];wage=number(task.get('sumOutputSalary'));effective=number(task.get('sumEffectiveTime'))
        amounts=[number(row.get('estimatedSalary')) for row in rows]
        all_approved=all(str(r.get('approvalStatus'))=='10' for r in rows)
        linked=str(task.get('projectId')) not in orphans
        salary_ok=wage is not None and all(v is not None for v in amounts) and abs(sum(amounts,Decimal(0))-wage)<Decimal('.005')
        coverage=linked and all_approved and salary_ok and all(r.get('productionWorkerKey') for r in rows)
        if not rows and wage!=0:coverage=False;issues.append('缺少对应报工，无法核实人工费对应人员')
        if not salary_ok:issues.append('报工工资与任务累计工资尚未对齐')
        if not all_approved:issues.append('存在未审批报工，需确认工资范围')
        if not linked:issues.append('存在无法关联到正确任务的报工')
        if rows and any(not r.get('productionWorkerKey') for r in rows):issues.append('报工缺少有效的生产人员标识')
        seconds=[]
        for row in rows:
            start,end=instant(row.get('starttimeofReport')),instant(row.get('endtimeofReport'))
            value=number(row.get('effectiveTime'))
            # Confirm seconds against actual timestamps; undocumented deduction
            # semantics are not guessed when rest/overtime/shutdown is nonzero.
            valid=(start is not None and end is not None and end>=start and value is not None
                and value==Decimal(str((end-start).total_seconds()))
                and all(number(row.get(k))==0 for k in ('planRestTime','overtimeTime','unplanShutdownTime')))
            seconds.append(value if valid else None)
        duration_ok=(linked and effective is not None and all(v is not None for v in seconds)
            and sum(seconds,Decimal(0))==effective)
        if not duration_ok:issues.append('有效时长与报工时段或任务累计数未对齐')
        result[str(task['id'])]={'registeredWorkerKeys':keys,'registeredCoverageComplete':bool(coverage),
            'registeredReports':len(rows),'sourceEffectiveHours':float(effective/3600) if duration_ok else None,
            'zeroTimeReports':sum(number(r.get('effectiveTime'))==0 for r in rows),
            'overlapReports':sum(str(r['id']) in overlapping for r in rows),
            'rosterComplete':False,'personHours':None,'issues':issues,
            'source':'blacklake_output_productionStaffId','basis':'registered_staff_not_confirmed_shift_roster'}
    return result
