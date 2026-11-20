"""Rank authorized chart metadata before it is sent to the planner."""
import os
import re


DEFAULT_CATALOG_LIMIT = 40

# Match question language against server-owned metric IDs and labels. Separate
# metrics from chart axes so, for example, a daily rate outranks a daily count.
_SIGNALS = (
    (r"不良率|缺陷率|defect rate", r"不良率|缺陷率|defect[_. ]rate", 12),
    (r"不良(?!率)|缺陷数|次品|defect (?:count|quantity)", r"不良数|缺陷数|defects|defect[_. ](?:count|qty)", 10),
    (r"(?<!不)良品(?!率)|产量|入库量|good qty|output", r"(?<!不)良品(?!率)|产量|\bgood\b|\boutput\b", 10),
    (r"出成率|投入量|\byield\b", r"出成率|投入量|\byield\b|\binput\b", 12),
    (r"单位成本|unit cost", r"单位成本|unit[_. ]cost", 12),
    (r"出成率|单位成本", r"成品入库|\bfinished\b|产量|\boutput\b", 4),
    (r"成品入库|finished goods", r"成品入库|\bfinished\b", 8),
    (r"各工序|按工序|出料(?:工序)?|by process", r"按工序|\bprocess\b", 9),
    (r"每日|每天|哪天|逐日|趋势|变化|daily|trend", r"每日|每天|日趋势|趋势|daily|trend|\bline\b", 6),
    (r"哪几个|哪款|排行|排名|最多的产品|top\b|ranking", r"排行|排名|ranking|top\b", 8),
)
_UNIT = re.compile(r"(?<![a-z])(?:kg|g|mg|ml|l|t)(?![a-z])|千克|公斤|克|吨|盒|袋|箱|件|包|瓶|桶|支|个(?=计量)", re.I)
_UNIT_ALIASES = {"千克": "kg", "公斤": "kg", "克": "g", "吨": "t"}
_UNIT_SCOPE = re.compile(r"按\s*([^\s，。]+?)\s*计量")
_TERM = re.compile(r"[a-z][a-z0-9]*|[\u3400-\u9fff]+")
_FIELDS = ("familyId", "familyLabel", "metric", "domain", "sourceKey", "chartType")


def catalog_limit(maximum):
    try:
        configured = int(os.getenv("FACTORY_CHART_PLANNER_CATALOG_LIMIT", DEFAULT_CATALOG_LIMIT))
    except ValueError:
        configured = DEFAULT_CATALOG_LIMIT
    return min(maximum, max(1, configured))


def _unit(value):
    normalized = value.strip().casefold()
    return _UNIT_ALIASES.get(normalized, normalized)


def _terms(value):
    tokens = set()
    for word in _TERM.findall(value):
        if word.isascii():
            if len(word) > 1:
                tokens.add(word)
        else:
            tokens.update(word[i:i + 2] for i in range(len(word) - 1))
    return tokens


def shortlist(question, catalog, required_families, limit):
    """Keep one best candidate per required family, then fill by relevance."""
    if len(catalog) <= limit:
        return catalog

    question = question.casefold()
    question_terms = _terms(question)
    scoped_unit = _UNIT_SCOPE.search(question)
    units = {_unit(scoped_unit[1])} if scoped_unit else {_unit(unit) for unit in _UNIT.findall(question)}
    signals = [(target, weight) for query, target, weight in _SIGNALS if re.search(query, question)]

    def score(item):
        metadata = " ".join(str(item.get(field, "")) for field in _FIELDS).casefold()
        unit = _unit(str(item.get("sourceQuantityUnit") or item.get("unit") or ""))
        score = min(8, len(question_terms & _terms(metadata)))
        score += sum(weight for target, weight in signals if re.search(target, metadata))
        if unit and unit in units:
            score += 7
        return score, item.get("priority") == "required"

    ranked = sorted(catalog, key=score, reverse=True)
    reserved = set()
    uncovered = set(required_families)
    for index, item in enumerate(ranked):
        family = item.get("familyId")
        if family in uncovered:
            reserved.add(index)
            uncovered.remove(family)
    remaining = [index for index in range(len(ranked)) if index not in reserved]
    indexes = reserved.union(remaining[:limit - len(reserved)])
    return [ranked[index] for index in sorted(indexes)]
