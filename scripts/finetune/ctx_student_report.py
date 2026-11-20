#!/usr/bin/env python3
"""Render student-v1-report.md tables from the JSON receipts written by ctx_student_train.py.

Usage: ctx_student_report.py --out <models/student-v1> --variant V > tables.md
Numbers only come from the receipts; nothing is recomputed or rounded upward.
"""
import argparse
import json
from pathlib import Path


def pct(x):
    return '—' if x is None else '%.1f%%' % (100 * x)


def ci(c):
    return '—' if not c or c[0] is None else '[%s, %s]' % (pct(c[0]), pct(c[1]))


def direct_table(title, groups):
    lines = ['#### ' + title, '', '| 分组 | 条数 | 原始全对 | 只读条数 | 直出 | 覆盖率 | 直出全对 | 直出准确率 | 直出到写/无目标 |', '|---|---|---|---|---|---|---|---|---|']
    for k, v in groups.items():
        lines.append('| %s | %d | %s | %d | %d | %s | %d | %s | %d |' % (
            k, v['n'], pct(v['raw_accuracy']), v['read_only_n'], v['direct'], pct(v['coverage']) if v['read_only_n'] else '—',
            v['correct'], pct(v['accuracy']), v['nonread_gold_direct']))
    return '\n'.join(lines) + '\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--variant', required=True)
    a = ap.parse_args()
    ev = json.loads((a.out / a.variant / 'evaluation.json').read_text(encoding='utf-8'))
    out = []
    for name in ('validation', 'adjudicated_validation', 'validation_all'):
        s = ev[name]
        d, r = s['direct'], s['raw']
        out.append('### %s（%d 条）\n' % (name, s['counts']['n']))
        out.append('| 指标 | 读数 | 95% 置信区间 |\n|---|---|---|')
        out.append('| 直出覆盖率（只读 %d 条中直出 %d） | %s | %s |' % (d['read_only_n'], d['read_only_direct'], pct(d['coverage']), ci(d['coverage_ci'])))
        out.append('| 直出部分目标+对话动作全对（%d/%d） | %s | %s |' % (d['correct'], d['direct'], pct(d['accuracy']), ci(d['accuracy_ci'])))
        out.append('| 直出部分目标准确率 | %s | %s |' % (pct(d['target_accuracy']), ci(d['target_ci'])))
        out.append('| 直出部分对话动作准确率 | %s | %s |' % (pct(d['act_accuracy']), ci(d['act_ci'])))
        out.append('| 直出选到写意图 | %d | |' % d['predicted_write_direct'])
        out.append('| 直出落在写任务样本上 | %d | |' % d['write_gold_direct'])
        out.append('| 直出落在写/无目标样本上 | %d | |' % d['nonread_gold_direct'])
        out.append('| 不设门槛：对话动作 / 目标 / 全对 | %s / %s / %s | |\n' % (pct(r['act_accuracy']), pct(r['target_accuracy']), pct(r['joint_accuracy'])))
        out.append(direct_table('按对话动作', s['by_act']))
        out.append(direct_table('按读写', s['by_read']))
        out.append(direct_table('按对话动作×读写', s['by_act_read']))
        out.append(direct_table('按语义族', s['by_family']))
        if s['direct_errors']:
            out.append('#### 直出错误\n\n| id | 句子 | 金标动作 | 金标意图 | 预测动作 | 预测意图 | p动作 | p目标 |\n|---|---|---|---|---|---|---|---|')
            for e in s['direct_errors']:
                out.append('| %s | %s | %s | %s | %s | %s | %.3f | %.3f |' % (e['id'], e['utterance'], e['gold_act'], e['gold'], e['act'], e['intent'], e['p_act'], e['p_target']))
            out.append('')
    g = ev['generalization']
    out.append('### 泛化\n')
    out.append('| 项 | 训练（一致银标） | 验证 | 差 |\n|---|---|---|---|')
    tr, va = ev['train_agreed']['raw'], ev['validation']['raw']
    for k, lab in (('act_accuracy', '对话动作'), ('target_accuracy', '目标'), ('joint_accuracy', '全对')):
        out.append('| %s | %s | %s | %+.1f pp |' % (lab, pct(tr[k]), pct(va[k]), 100 * (tr[k] - va[k])))
    out.append('\n最差五个族（验证全集，按不设门槛全对率）：\n\n| 族 | 条数 | 全对 | 区间 | 直出 | 直出准确率 |\n|---|---|---|---|---|---|')
    for f in g['worst_families_validation']:
        out.append('| %s | %d | %s | %s | %d | %s |' % (f['family'], f['n'], pct(f['raw_accuracy']), ci(f['raw_ci']), f['direct'], pct(f['accuracy'])))
    print('\n'.join(out))


if __name__ == '__main__':
    main()
