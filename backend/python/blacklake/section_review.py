"""Persist only validated shared 27B review, bound to current rule facts."""
from .secrets import load_local_secret, save_local_secret
from smartbi.services.alert_local_ai import fingerprint, facts_for, validate_response, representative_groups


def for_section(report, items):
    ai=report.get('ai',{})
    expected={a['id'] for a in representative_groups({'items':items}).values()}
    reviews=[r for r in ai.get('reviews',[]) if r['alertId'] in expected]
    result={**ai,'reviews':reviews,'sectionRepresentativeFacts':len(expected),
            'reviewedSectionFacts':len(reviews),'representativeOnly':True}
    if not expected:
        result.update(status='not_applicable',message='该板块没有适用的AI核对事实；计算说明仍来自规则和当前证据')
    elif ai.get('status') in ('complete','partial'):
        complete={r['alertId'] for r in reviews}==expected
        result.update(status='complete' if complete else 'partial',
                      message=f'本板块已核对 {len(reviews)}/{len(expected)} 类代表问题；并非逐记录原因确认')
    return result


def save(report, path):
    ai = report.get('ai', {})
    if ai.get('status') != 'complete' or ai.get('model') != 'qwen3.8:27b':
        return False
    if ai.get('sourceRecordsSent') != 0 or ai.get('externalProviderCalls') != 0:
        return False
    facts = facts_for(report)
    validate_response({'reviews': [{k: r[k] for k in ('alertId','check','priority')} for r in ai['reviews']]}, facts)
    save_local_secret(path, {'fingerprint': fingerprint(report), 'facts': facts, 'ai': ai})
    return True


def attach(report, path):
    report['ai'] = {'status':'not_run','message':'规则已完成；当前选择27B，等待本地核对'}
    if not path.exists():
        return
    try:
        saved = load_local_secret(path)
        ai = saved['ai']
        facts = facts_for(report)
        if (saved['fingerprint'] != fingerprint(report) or saved['facts'] != facts
                or ai['model'] != 'qwen3.8:27b' or ai['status'] != 'complete'
                or ai.get('sourceRecordsSent') != 0 or ai.get('externalProviderCalls') != 0):
            raise ValueError('review binding mismatch')
        validate_response({'reviews': [{k:r[k] for k in ('alertId','check','priority')} for r in ai['reviews']]}, facts)
        report['ai'] = ai
    except Exception:
        report['ai'] = {'status':'stale','message':'本地核对与当前事实不一致或未通过校验，规则仍可用'}
