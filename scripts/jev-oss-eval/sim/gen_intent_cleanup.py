"""由 intent_cleanup_plan.PLAN 生成清单 (markdown) 与迁移 SQL, 生成前逐条校验:
  · 每个代码在 prod 在用目录里 (intents_audit.csv)
  · 删除 / 停用 / 合并只能落在 FACTORY 意图上; COMMON 只能 SCOPE_OUT (餐饮不动)
  · 合并目标存在、在用、可执行
  · 同一个代码只出现一次
用法: python gen_intent_cleanup.py OUT.sql OUT.md
"""
from __future__ import annotations

import collections
import csv
import json
import sys

from bailian import OUT
from intent_cleanup_plan import PLAN, PLAN2


def main() -> int:
    sql_path, md_path = sys.argv[1], sys.argv[2]
    batch = sys.argv[3] if len(sys.argv) > 3 else "1"
    done_before = set()
    plan = PLAN
    if batch == "2":
        done_before = {c for c, a, *_ in PLAN if a != "REVIEW"}
        plan = PLAN2
    rows = {r["intent_code"]: r for r in csv.DictReader(open(OUT / "intents_audit.csv", encoding="utf-8"))}
    rep = {r["intent_code"]: r for r in json.load(open(OUT / "intent_audit_report.json", encoding="utf-8"))["rows"]}
    errs = []
    seen = collections.Counter(c for c, *_ in plan)
    errs += [f"重复出现: {c}" for c, n in seen.items() if n > 1]
    for code, action, reason, target in plan:
        r = rows.get(code)
        if code in done_before:
            errs.append(f"{code} 第一批已处理")
            continue
        if r is None:
            errs.append(f"不在在用目录里: {code}")
            continue
        bt = r["business_type"]
        merge_common_ok = (action == "MERGE_INTO" and bt == "COMMON" and batch == "2"
                           and rows.get(target, {}).get("business_type") == "COMMON")
        if action in ("SOFT_DELETE", "DEACTIVATE", "MERGE_INTO") and bt != "FACTORY" and not merge_common_ok:
            errs.append(f"{code} 是 {bt}, 不能 {action} (餐饮不动; COMMON 只能 SCOPE_OUT)")
        if action == "SCOPE_OUT" and bt != "COMMON":
            errs.append(f"{code} 是 {bt}, SCOPE_OUT 只用于 COMMON")
        if action == "MERGE_INTO":
            t = rep.get(target)
            if t is None:
                errs.append(f"{code} 的合并目标 {target} 不在在用目录")
            elif t["exec"] not in ("TOOL_OK", "SKILL"):
                errs.append(f"{code} 的合并目标 {target} 不可执行 ({t['exec']})")
            elif any(c == target for c, a, *_ in plan if a != "REVIEW") or target in done_before:
                errs.append(f"{code} 的合并目标 {target} 自己也在计划里")
    if errs:
        print("\n".join("❌ " + e for e in errs))
        return 1

    acts = [p for p in plan if p[1] != "REVIEW"]
    lines = [
        "-- 工厂意图目录清理 (owner 2026-10-03 批准)。由评测仓 sim/gen_intent_cleanup.py 从 intent_cleanup_plan.py 生成,",
        "-- 生成前已校验: 代码都在在用目录 / 删除停用合并只落在 FACTORY / COMMON 只移出工厂目录 / 合并目标可执行。",
        "-- ⛔ 餐饮不动: COMMON 意图改为 RESTAURANT, 餐饮看到的集合逐条不变。",
        "-- 删除一律软删 (deleted_at), 停用是 is_active=false —— 都可恢复。每行在 metadata.cleanup_20261003 留原因。",
        "",
        "CREATE OR REPLACE FUNCTION pg_temp.append_keywords(old jsonb, add jsonb) RETURNS jsonb AS $$",
        "    SELECT COALESCE(jsonb_agg(v ORDER BY first_pos), '[]'::jsonb)",
        "    FROM (SELECT v, MIN(pos) AS first_pos",
        "          FROM jsonb_array_elements_text(COALESCE(old, '[]'::jsonb) || COALESCE(add, '[]'::jsonb)) WITH ORDINALITY AS t(v, pos)",
        "          GROUP BY v) s",
        "$$ LANGUAGE sql IMMUTABLE;",
        "",
    ]

    def note(action, reason):
        return ("metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', "
                f"jsonb_build_object('action', '{action}', 'reason', '{reason}'))")

    for code, action, reason, target in acts:
        bt = rows[code]["business_type"]
        where = f"WHERE intent_code = '{code}' AND business_type = '{bt}' AND deleted_at IS NULL"
        if action == "MERGE_INTO":
            lines += [
                f"-- {code} → {target}: {reason}",
                "UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),",
                "    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()",
                f"FROM ai_intent_configs s WHERE s.intent_code = '{code}' AND s.deleted_at IS NULL",
                f"  AND t.intent_code = '{target}' AND t.deleted_at IS NULL;",
                f"UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), {note('MERGE_INTO:' + target, reason)}",
                f"{where};",
                "",
            ]
        elif action == "SOFT_DELETE":
            lines += [f"-- {code}: {reason}",
                      f"UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), {note(action, reason)}",
                      f"{where};", ""]
        elif action == "DEACTIVATE":
            lines += [f"-- {code}: {reason}",
                      f"UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), {note(action, reason)}",
                      f"{where};", ""]
        elif action == "HARDEN":
            lines += [f"-- {code}: {reason} —— 保留在聊天里, 但必须有权限 ({target}) + WriteGuard 二次确认",
                      "UPDATE ai_intent_configs SET sensitivity_level = 'HIGH',",
                      f"    required_permission = COALESCE(NULLIF(required_permission, ''), '{target}'),",
                      f"    updated_at = NOW(), {note('HARDEN', reason)}",
                      f"{where};", ""]
        elif action == "SCOPE_OUT":
            lines += [f"-- {code}: {reason}",
                      f"UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), "
                      f"{note('SCOPE_OUT:COMMON->RESTAURANT', reason)}",
                      f"{where};", ""]
    open(sql_path, "w", encoding="utf-8", newline="\n").write("\n".join(lines))

    n_before = len(rows) - sum(1 for c in done_before)
    n_after = n_before - sum(1 for p in acts if p[1] != "HARDEN")
    md = [f"# 工厂意图目录清理清单 (2026-10-03, 第 {batch} 批)", "",
          f"工厂可见在用意图 **{n_before} → {n_after}**（本批 {len(acts)} 条；另有 "
          f"{sum(1 for p in plan if p[1] == 'REVIEW')} 条待你判断，本批不动）。", "",
          "| 动作 | 含义 | 条数 |", "|---|---|---|"]
    meaning = {"HARDEN": "保留但收紧 (权限 + 严格确认)", "DEACTIVATE": "停用 (可恢复)", "MERGE_INTO": "合并后软删 (关键词并入目标)", "SCOPE_OUT": "移出工厂目录 (餐饮不变)",
               "SOFT_DELETE": "软删", "REVIEW": "待判断"}
    cnt = collections.Counter(p[1] for p in plan)
    md += [f"| {a} | {meaning[a]} | {cnt[a]} |" for a in ("DEACTIVATE", "MERGE_INTO", "SCOPE_OUT", "HARDEN", "SOFT_DELETE", "REVIEW") if cnt[a]]
    for a in ("DEACTIVATE", "MERGE_INTO", "SCOPE_OUT", "HARDEN", "REVIEW"):
        xs = [p for p in plan if p[1] == a]
        if not xs:
            continue
        md += ["", f"## {meaning[a]}（{len(xs)}）", "", "| 意图 | 名称 | 原因 |" + (" 并入 |" if a == "MERGE_INTO" else ""),
               "|---|---|---|" + ("---|" if a == "MERGE_INTO" else "")]
        for code, _, reason, target in xs:
            md.append(f"| `{code}` | {rows[code]['intent_name']} | {reason} |" + (f" `{target}` |" if a == "MERGE_INTO" else ""))
    open(md_path, "w", encoding="utf-8", newline="\n").write("\n".join(md) + "\n")
    print(f"OK: {len(acts)} 条动作 / {cnt['REVIEW']} 条待判断; 目录 {n_before} -> {n_after}", dict(cnt))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
