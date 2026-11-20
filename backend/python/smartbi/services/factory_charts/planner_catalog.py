"""Rank authorized chart metadata before it is sent to the planner."""
import os
import re

from smartbi.services.external_production import SOURCE_KEY


DEFAULT_CATALOG_LIMIT = 40

# Match question language against server-owned metric IDs and labels. Separate
# metrics from chart axes so, for example, a daily rate outranks a daily count.
_SIGNALS = (
    (r"不良率|缺陷率|defect rate", r"不良率|缺陷率|defect[_. ]rate", 12),
    (r"不良(?!率)|缺陷数|次品|defect (?:count|quantity)", r"不良数|缺陷数|defects|defect[_. ](?:count|qty)", 12),
    (r"(?<!不)良品(?!率)|产量|入库量|good qty|output", r"(?<!不)良品(?!率)|产量|\bgood\b|\boutput\b", 10),
    (r"出成率|投入量|\byield\b", r"出成率|投入量|\byield\b|\binput\b", 12),
    (r"单位成本|unit cost", r"单位成本|unit[_. ]cost", 12),
    (r"出成率|单位成本", r"成品入库|\bfinished\b|产量|\boutput\b", 4),
    (r"出成率|单位成本", rf"{SOURCE_KEY}\.process\.good\b", 2),
    (r"成品入库|finished goods", r"成品入库|\bfinished\b", 8),
    (r"各工序|按工序|出料(?:工序)?|by process", r"按工序|\bprocess\b", 9),
    (r"出料.{0,20}比成品入库", r"按工序|\bprocess\b", 12),
    (r"每日|每天|哪天|逐日|趋势|变化|daily|trend", r"每日|每天|日趋势|趋势|daily|trend|\bline\b", 6),
    (r"哪几个|哪款|排行|排名|最多的产品|top\b|ranking", r"排行|排名|ranking|top\b", 16),
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


def _ranked_catalog(question, catalog, required_families):
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
        if score and unit and unit in units:
            score += 7
        if item.get("familyId") in required_families:
            score += 1  # A verified match for the requested joint relationship.
        return score, item.get("priority") == "required"

    ranked = [(item, *score(item)) for item in catalog]
    ranked.sort(key=lambda entry: (entry[1], entry[2]), reverse=True)
    return [(item, relevance) for item, relevance, _ in ranked]


def shortlist(question, catalog, required_families, limit):
    """Keep one best candidate per required family, then fill by relevance."""
    if len(catalog) <= limit:
        return catalog

    ranked = [item for item, _ in _ranked_catalog(question, catalog, required_families)]
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


def rule_selection(question, catalog, required_families, limit):
    """Choose positively scored authorized IDs, once per family."""
    scoped_unit = _UNIT_SCOPE.search(question.casefold())
    if scoped_unit and not required_families:
        unit = _unit(scoped_unit[1])
        catalog = [item for item in catalog
                   if _unit(str(item.get("sourceQuantityUnit") or item.get("unit") or "")) == unit]

    # These Blacklake charts carry the source's explicit missing-input
    # limitation. Keep the evidence on that source and metric.
    if not required_families and re.search(r"出成率|成品入库.*单位成本", question):
        evidence = [item for item in catalog if item.get("familyId") in {
            f"{SOURCE_KEY}.finished.good", f"{SOURCE_KEY}.process.good",
        }]
        if evidence:
            catalog = evidence

    selected = []
    families = set()
    for item, score in _ranked_catalog(question, catalog, required_families):
        candidate_id = item.get("id")
        family = item.get("familyId") or candidate_id
        if score <= 0 or not candidate_id or family in families:
            continue
        families.add(family)
        selected.append(candidate_id)
        if len(selected) == limit:
            break
    return selected if required_families.issubset(families) else []
