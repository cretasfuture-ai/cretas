"""Choose a compact default set while keeping every qualified view selectable."""


def metric_group(candidate, guided=True):
    analysis = candidate.get('analysis') or {}
    if guided and analysis.get('familyId'):
        return str(analysis['familyId'])
    key = candidate['id'].split(':')[0]
    for name in ('budget_achievement', 'variance_analysis', 'bullet_chart'):
        if name in key:
            return key.split(name)[0] + 'budget-main'
    return key


def default_selection(candidates, limit=6, guided=False):
    seen, selected = set(), []
    for candidate in candidates:
        group = metric_group(candidate, guided=guided)
        if group in seen:
            continue
        seen.add(group)
        selected.append(candidate['id'])
        if len(selected) == limit:
            break
    return selected
