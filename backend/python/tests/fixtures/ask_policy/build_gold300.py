# -*- coding: utf-8 -*-
"""双人金标 -> backend/python/tests/fixtures/ask_policy/gold300.json (+ items 供跑批)。

输入 (仓库外, 只读): 史浩禾 answers-latest.json、Steve steve-answers.json、题目对照表.json。
映射沿用 #1711/#1719 口径: direct->direct, ask->ask, show->show, other->unscored;
备注优先于按钮 (写明「先给默认/先展示, 待用户确认」的算 show)。
other 与带备注的 direct 在 SHI_NOTE_MAP 里逐条人工归类, 理由写进 mapped_by。
用法: python build_gold300.py <gold-300 目录> <输出目录> <third-batch-calibrate 目录 (含 build_calib.py)> <catalog_readwrite.json>
(输入都在仓库外, 路径由调用者给; 本文件只记录归类依据, 不带本机路径)
"""
import json
import pathlib
import sys

GOLD = pathlib.Path(sys.argv[1])
OUT = pathlib.Path(sys.argv[2])
CAL = pathlib.Path(sys.argv[3])
CATALOG = pathlib.Path(sys.argv[4])
sys.path.insert(0, str(CAL))
import build_calib as bc  # noqa: E402  (ROLE / render_state / MANUAL_HISTORY, 与前两批同一份)

# 史浩禾: other 和「按钮与备注冲突」的题, 逐条看备注归类。key=题号 -> (动作, 依据)
SHI_NOTE_MAP = {
    2: ('show', '备注: 先给产能汇总的预设字段, 问用户是否确认范围'),
    5: ('ask', '备注: 不再问看不看排班, 直接问怎么改'),
    6: ('show', '备注: 先列数量, 再追问是否要分析'),
    7: ('ask', '备注: 既要问哪款产品, 也要问按国标还是企标'),
    21: ('unscored', 'other 且没写备注'),
    35: ('show', '备注: 改算先展示结果, 不录入, 等用户决定'),
    39: ('show', '按钮 direct, 备注: 先展示字段范围, 待确认后生成'),
    45: ('show', '按钮 direct, 备注: 先给默认答案(最近一次投料失败批次), 再反问是不是这一批'),
    49: ('show', '按钮 direct, 备注: 先默认给经营复盘, 再问范围对不对、要补什么字段'),
    76: ('ask', '备注: 自动=所有风险等级全自动, 应先问并告知后果, 问是否按风险分级'),
    87: ('show', '备注: 不反问有几个批次, 读库展示已知批次让用户选'),
    120: ('ask', '备注: 只需问打印哪一张领料单'),
    133: ('direct', '备注: 自行查库判断玉米是产品还是采购品, 直接给价格, 不问'),
    142: ('unscored', 'other, 备注讨论「边界」, 没给动作'),
    143: ('direct', '备注: 默认时间范围应更长, 按判定条件直接去找, 没说要问'),
    167: ('unscored', 'other, 备注「边界需要分析讨论」, 没给动作'),
    181: ('ask', '备注: 单子范围太广, 先缩小范围再询问是哪种单、做什么操作'),
    242: ('show', '备注: 取消转单, 找到并展示发货单, 再问按什么要求分批'),
    256: ('direct', '备注: 上文是包装车间, 默认就是包装车间, 不问'),
    266: ('direct', '备注: 查它是供应商还是客户并展示对应单子, 不退还给用户'),
}
# c=direct 带备注, 但备注只是补充默认范围/排序/不遮挡, 没要求先确认: 保持按钮 direct (列在这里是为了证明逐条看过)
SHI_NOTE_KEEP_DIRECT = {105, 125, 126, 144, 178, 273}
# 带备注的 ask 题, 备注与按钮一致
SHI_NOTE_KEEP_ASK = {81, 118}


def main():
    qs = {x['qno']: x for x in json.loads((GOLD / '题目对照表.json').read_text('utf-8'))}
    cat = json.loads(CATALOG.read_text('utf-8'))
    names = {r['code']: r['name'] for r in cat}
    shi = {a['no']: a for a in json.loads((GOLD / 'external' / 'answers-latest.json').read_text('utf-8'))}
    ste = {int(k): v for k, v in json.loads((GOLD / 'steve' / 'steve-answers.json').read_text('utf-8')).items()}
    assert sorted(shi) == sorted(ste) == list(range(1, 301))
    rows, items = [], []
    for no in range(1, 301):
        q, a, s = qs[no], shi[no], ste[no]
        note = (a.get('note') or '').strip()
        if a['c'] == 'other' and no not in SHI_NOTE_MAP:
            raise SystemExit('史浩禾 other 未归类: %d' % no)
        if note and no not in SHI_NOTE_MAP and no not in SHI_NOTE_KEEP_DIRECT and no not in SHI_NOTE_KEEP_ASK:
            raise SystemExit('带备注未逐条看: %d' % no)
        if no in SHI_NOTE_MAP:
            shi_exp, why = SHI_NOTE_MAP[no]
        else:
            shi_exp, why = a['c'], '按钮'
        ste_exp = 'unscored' if s['choice'] == 'other' else s['choice']
        if shi_exp == 'unscored' and ste_exp == 'unscored':
            status = 'unscored'
        elif shi_exp == 'unscored':
            status = 'steve_only'
        elif ste_exp == 'unscored':
            status = 'shi_only'
        elif shi_exp == ste_exp:
            status = 'agree'
        else:
            status = 'disputed'
        hist = bc.MANUAL_HISTORY.get(no) or bc.render_state(q.get('state'), names)
        role = bc.ROLE.get(q['page_role'], 'factory_super_admin')
        rows.append(dict(
            qno=no, id='Q%03d' % no, utterance=q['utterance'], role=role, rw=q['rw'], history=hist,
            steve=dict(choice=s['choice'], note=(s.get('note') or '').strip(), expected=ste_exp),
            shi=dict(choice=a['c'], note=note, expected=shi_exp, mapped_by=why),
            status=status,
            # 计分口径: 激进档 = Steve 答案 (一致题 + Steve 独有题); 保守档 = 史浩禾答案 (含与 Steve 有争议的题)
            aggressive_scored=status in ('agree', 'steve_only'),
            conservative_scored=shi_exp != 'unscored',
        ))
        items.append(dict(id='Q%03d' % no, role=role, turns=[dict(input=q['utterance'], history=hist)]))
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'gold300.json').write_text(json.dumps(rows, ensure_ascii=False, indent=1) + '\n', 'utf-8', newline='\n')
    (OUT / 'items300.json').write_text(json.dumps(items, ensure_ascii=False), 'utf-8')
    import collections
    print(collections.Counter(r['status'] for r in rows))
    print('shi scored', sum(r['conservative_scored'] for r in rows), collections.Counter(r['shi']['expected'] for r in rows))
    print('steve', collections.Counter(r['steve']['expected'] for r in rows))
    print('agg scored', sum(r['aggressive_scored'] for r in rows))
    print(collections.Counter((r['steve']['expected'], r['shi']['expected']) for r in rows if r['status'] == 'disputed'))


main()
