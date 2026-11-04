"""scripts/training_filter_replay.py — the offline harness for the acceptance replay
(过滤规则待办第 5 条). It must give the same verdicts as the capture / export rules and
use three-state exit codes (0 ok / 1 expectation failed / 2 nothing measured)."""
from __future__ import annotations

import csv
import json

from scripts import training_filter_replay as replay

ROW_12884_INPUT = ("6. CLK-W-20260717-57628 - 盐葱横膈膜/滚揉\n   状态: IN_PROGRESS, 成本: 1.73, 良品率: 92.86%\n"
                   "7. CLK-W-20260717-44285 - 盐葱横膈膜/装盒未贴标")

ROWS = [
    {"id": "12884", "factory_id": "", "source": "chat_qa", "business_type": "unknown", "metadata": "{}",
     "input_text": ROW_12884_INPUT, "teacher_output": "考虑到涉及澳洲谷饲牛肉原料"},
    {"id": "223", "factory_id": "F006", "source": "chat_qa", "business_type": "factory",
     "metadata": json.dumps({"training_rule": "2026-11-03"}),
     "input_text": "N-1780458739767-4DF1C20A-39879 - 叮咚好食光轻卤门腔（猪舌）120g", "teacher_output": "正常"},
    {"id": "1", "factory_id": "F006", "source": "chat_qa", "business_type": "factory",
     "metadata": json.dumps({"training_rule": "2026-11-03"}),
     "input_text": "398礼盒今天发了多少", "teacher_output": "发了 12 箱"},
    {"id": "2", "factory_id": "F006", "source": "chat_qa", "business_type": "factory",
     "metadata": json.dumps({"training_rule": "2026-11-03"}),
     "input_text": "员工周报在哪看", "teacher_output": "在报表页"},
    {"id": "16743", "factory_id": "", "source": "drill", "business_type": "unknown",
     "metadata": json.dumps({"desensitized": "true", "training_rule": "drill"}),
     "input_text": "演练脱敏样本", "teacher_output": "演练"},
    {"id": "9", "factory_id": "SEED_F", "source": "synthesis", "business_type": "factory", "metadata": "",
     "input_text": "合成问题", "teacher_output": "合成答案"},
]
LISTS = {"F006": {"products": [{"id": "PT_F006_LSM_4ba547a827ea800d", "code": "PTSHHPSKGYLYXGS0125",
                                "name": "398", "category": "原切"},
                               {"id": "PT_F006_LSM_61a43292299498bb", "code": "PTSHHPSKGYLYXGS0124",
                                "name": "398礼盒", "category": "原切"}],
                  "people": ["王五"], "customers": []}}


def _files(tmp_path, rows=ROWS):
    rows_path = tmp_path / "rows.csv"
    with open(rows_path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(ROWS[0]))
        w.writeheader()
        w.writerows(rows)
    lists_path = tmp_path / "lists.json"
    lists_path.write_text(json.dumps(LISTS, ensure_ascii=False), encoding="utf-8")
    return str(rows_path), str(lists_path)


def test_verdicts_and_reasons(tmp_path, capsys):
    rows, lists = _files(tmp_path)
    out = tmp_path / "v.jsonl"
    rc = replay.main(["--rows", rows, "--lists-json", lists, "--out", str(out),
                      "--expect", "12884:blocked", "--expect", "223:pass", "--expect", "2:pass"])
    assert rc == 0
    v = {r["id"]: r for r in map(json.loads, out.read_text(encoding="utf-8").splitlines())}
    assert v["12884"]["content_blocked"] and "process_value_in_input" in v["12884"]["reasons"]
    assert "no_factory" in v["12884"]["reasons"] and not v["12884"]["usable"]
    assert v["223"]["usable"] is True                                  # 398 inside a batch number: no hit
    assert v["1"]["reasons"] == ["process_secret_term"]                # full name hit
    assert v["2"]["usable"] is True                                    # names nobody
    assert "no_real_rule_version" in v["16743"]["reasons"]
    assert v["9"]["usable"] is True                                    # synthetic seed
    summary = json.loads(capsys.readouterr().out.split("EXPECT")[0])
    assert summary["rows"] == 6 and summary["content_blocked"] == 1


def test_three_state_exit_codes(tmp_path):
    rows, lists = _files(tmp_path)
    assert replay.main(["--rows", rows, "--lists-json", lists, "--expect", "223:blocked"]) == 1
    assert replay.main(["--rows", rows, "--lists-json", lists, "--expect", "777:blocked"]) == 2
    empty_rows, _ = _files(tmp_path, rows=[])
    assert replay.main(["--rows", empty_rows, "--lists-json", lists]) == 2


def test_harness_agrees_with_the_export_rule(tmp_path):
    from scripts.export_distillation_dataset import passes_training_rule

    by_factory = replay.lists_from_json(LISTS)
    for r in ROWS:
        if r["factory_id"] == "SEED_F":
            continue
        row = dict(r, metadata=json.loads(r["metadata"]) if r["metadata"] else {})
        assert replay.explain_row(r, by_factory)["usable"] == passes_training_rule(row, by_factory), r["id"]
