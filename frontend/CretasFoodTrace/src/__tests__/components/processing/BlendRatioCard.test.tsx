/**
 * 缺口 06 配比核对 (App 报工)。三层:
 * ① utils/blendRatio 判定 (与后端 BlendRatioCheck 同口径);
 * ② BlendRatioCard: 超差标红、原因按钮、「其他」才出输入框;
 * ③ MaterialBatchPicker 把任务选项 (含份额) 回传给父组件 —— 屏幕靠它才知道每批是哪种料、份额多少。
 */
import React, { useState } from 'react';
import { Text } from 'react-native';
import { render, fireEvent, waitFor, screen } from '@testing-library/react-native';
import BlendRatioCard from '../../../components/processing/BlendRatioCard';
import MaterialBatchPicker from '../../../components/processing/MaterialBatchPicker';
import { yieldReportApi } from '../../../services/api/yieldReportApi';
import {
  BLEND_REASONS,
  blendInputsFromRefs,
  blendRatioPayload,
  blendReasonBlockedReason,
  evaluateBlend,
  type BlendMaterialOption,
} from '../../../utils/blendRatio';

jest.mock('../../../services/api/yieldReportApi', () => ({
  yieldReportApi: { listTaskMaterialOptions: jest.fn() },
}));

jest.mock('../../../services/api/materialBatchApiClient', () => ({
  materialBatchApiClient: {
    getBatchesByStatus: jest.fn(),
    getBatchById: jest.fn(),
    getFefoBatches: jest.fn(),
  },
}));

const OPTIONS = new Map<string, BlendMaterialOption>([
  ['MB-BEEF-1', { materialTypeId: 'RM-BEEF', materialName: '牛肉', inputShareRatio: 30 }],
  ['MB-BEEF-2', { materialTypeId: 'RM-BEEF', materialName: '牛肉', inputShareRatio: 30 }],
  ['MB-PORK', { materialTypeId: 'RM-PORK', materialName: '猪肉', inputShareRatio: 30 }],
  ['MB-FAT', { materialTypeId: 'RM-FAT', materialName: '脂肪', inputShareRatio: 40 }],
  ['MB-SALT', { materialTypeId: 'RM-SALT', materialName: '盐', inputShareRatio: null }],
]);

const steveRefs = [
  { materialBatchId: 'MB-BEEF-1', quantity: 10, unit: 'kg' },
  { materialBatchId: 'MB-BEEF-2', quantity: 15, unit: 'kg' },   // 同一种料分两批领, 按料汇总
  { materialBatchId: 'MB-PORK', quantity: 41.7, unit: 'kg' },
  { materialBatchId: 'MB-FAT', quantity: 33.3, unit: 'kg' },
  { materialBatchId: 'MB-SALT', quantity: 2, unit: 'kg' },      // 无份额: 不进分母
];

describe('blendRatio utils', () => {
  it('Steve 的例子: 按原料汇总后 25/41.7/33.3 超差, 未选原因拦, 选了放行并带 payload', () => {
    const ev = evaluateBlend(blendInputsFromRefs(steveRefs, OPTIONS, 'kg'));
    expect(ev.status).toBe('OVER');
    if (ev.status !== 'OVER') return;
    expect(ev.lines.map((l) => [l.name, l.actualPercent])).toEqual([['牛肉', 25], ['猪肉', 41.7], ['脂肪', 33.3]]);
    expect(blendReasonBlockedReason(ev, '', '')).toContain('猪肉');
    expect(blendReasonBlockedReason(ev, '其他', '')).toContain('具体原因');
    expect(blendReasonBlockedReason(ev, '称重误差', '')).toBeNull();
    expect(blendRatioPayload(ev, '称重误差', '')).toEqual({ reason: '称重误差' });
  });

  it('容差内 / 边界 2pp 放行, 不发 payload; 2.5pp 超差', () => {
    const within = evaluateBlend(blendInputsFromRefs([
      { materialBatchId: 'MB-BEEF-1', quantity: 32, unit: 'kg' },
      { materialBatchId: 'MB-PORK', quantity: 28, unit: 'kg' },
      { materialBatchId: 'MB-FAT', quantity: 40, unit: 'kg' },
    ], OPTIONS, 'kg'));
    expect(within.status).toBe('WITHIN');
    expect(blendRatioPayload(within, '称重误差', '')).toBeUndefined();
    const over = evaluateBlend(blendInputsFromRefs([
      { materialBatchId: 'MB-BEEF-1', quantity: 32.5, unit: 'kg' },
      { materialBatchId: 'MB-PORK', quantity: 27.5, unit: 'kg' },
      { materialBatchId: 'MB-FAT', quantity: 40, unit: 'kg' },
    ], OPTIONS, 'kg'));
    expect(over.status).toBe('OVER');
  });

  it('只领了一种带份额的料 / 选项还没加载 → 不适用', () => {
    expect(evaluateBlend(blendInputsFromRefs([{ materialBatchId: 'MB-PORK', quantity: 10, unit: 'kg' }], OPTIONS, 'kg')).status)
      .toBe('NOT_APPLICABLE');
    expect(evaluateBlend(blendInputsFromRefs(steveRefs, new Map(), 'kg')).status).toBe('NOT_APPLICABLE');
  });

  it('非 kg: 全「个」按个数比; kg+g 折克; 个+kg 跳过不拦', () => {
    const count = new Map<string, BlendMaterialOption>([
      ['E', { materialTypeId: 'EGG', materialName: '蛋', inputShareRatio: 30 }],
      ['B', { materialTypeId: 'BUN', materialName: '饼', inputShareRatio: 70 }],
    ]);
    expect(evaluateBlend(blendInputsFromRefs([
      { materialBatchId: 'E', quantity: 40, unit: '个' }, { materialBatchId: 'B', quantity: 60, unit: '个' },
    ], count, '个')).status).toBe('OVER');
    expect(evaluateBlend(blendInputsFromRefs([
      { materialBatchId: 'E', quantity: 0.3, unit: 'kg' }, { materialBatchId: 'B', quantity: 700, unit: 'g' },
    ], count, 'kg')).status).toBe('WITHIN');
    const skipped = evaluateBlend(blendInputsFromRefs([
      { materialBatchId: 'E', quantity: 9, unit: '个' }, { materialBatchId: 'B', quantity: 1, unit: 'kg' },
    ], count, 'kg'));
    expect(skipped.status).toBe('SKIPPED');
    expect(blendReasonBlockedReason(skipped, '', '')).toBeNull();
  });
});

function Harness() {
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const ev = evaluateBlend(blendInputsFromRefs(steveRefs, OPTIONS, 'kg'));
  const blocked = blendReasonBlockedReason(ev, reason, note);
  return (
    <>
      <BlendRatioCard evaluation={ev} reason={reason} note={note} onReasonChange={setReason} onNoteChange={setNote} />
      <BlendStatus blocked={blocked} />
    </>
  );
}

function BlendStatus({ blocked }: { blocked: string | null }) {
  return <Text testID="blend-status">{blocked ?? 'OK'}</Text>;
}

describe('BlendRatioCard', () => {
  it('超差标红写明多/少几个百分点; 5 个原因按钮; 选「其他」才出输入框, 写了才放行', () => {
    render(<Harness />);
    expect(screen.getByTestId('blend-ratio-dev-RM-PORK').props.children).toBe('多 11.7 个百分点');
    expect(screen.getByTestId('blend-ratio-dev-RM-BEEF').props.children).toBe('少 5 个百分点');
    for (const r of BLEND_REASONS) expect(screen.getByTestId(`blend-ratio-reason-${r}`)).toBeTruthy();
    expect(screen.getByTestId('blend-status').props.children).toContain('请在「配比核对」里选一个原因');
    expect(screen.queryByTestId('blend-ratio-note')).toBeNull();

    fireEvent.press(screen.getByTestId('blend-ratio-reason-其他'));
    expect(screen.getByTestId('blend-ratio-note')).toBeTruthy();
    expect(screen.getByTestId('blend-status').props.children).toContain('具体原因');
    fireEvent.changeText(screen.getByTestId('blend-ratio-note'), '客户要求多放猪肉');
    expect(screen.getByTestId('blend-status').props.children).toBe('OK');
  });

  it('容差内: 只显示核对, 没有原因区', () => {
    const ev = evaluateBlend(blendInputsFromRefs([
      { materialBatchId: 'MB-BEEF-1', quantity: 30, unit: 'kg' },
      { materialBatchId: 'MB-PORK', quantity: 30, unit: 'kg' },
      { materialBatchId: 'MB-FAT', quantity: 40, unit: 'kg' },
    ], OPTIONS, 'kg'));
    render(<BlendRatioCard evaluation={ev} reason="" note="" onReasonChange={() => undefined} onNoteChange={() => undefined} />);
    expect(screen.getByTestId('blend-ratio-card')).toBeTruthy();
    expect(screen.queryByTestId('blend-ratio-reason-area')).toBeNull();
  });

  it('不适用时整张卡不渲染', () => {
    render(<BlendRatioCard evaluation={{ status: 'NOT_APPLICABLE' }} reason="" note="" onReasonChange={() => undefined} onNoteChange={() => undefined} />);
    expect(screen.queryByTestId('blend-ratio-card')).toBeNull();
  });
});

describe('MaterialBatchPicker → 配比核对 接线', () => {
  it('任务选项加载后把原料类型与份额回传给父组件', async () => {
    const options = [
      { id: 'MB-BEEF-1', batchNumber: 'B-1', materialTypeId: 'RM-BEEF', materialName: '牛肉', currentQuantity: 50, quantityUnit: 'kg', warehouseId: 'WH', inputShareRatio: 30 },
      { id: 'MB-PORK', batchNumber: 'B-2', materialTypeId: 'RM-PORK', materialName: '猪肉', currentQuantity: 50, quantityUnit: 'kg', warehouseId: 'WH', inputShareRatio: 70 },
    ];
    (yieldReportApi.listTaskMaterialOptions as jest.Mock).mockResolvedValue({ success: true, data: options });
    const onOptionsLoaded = jest.fn();
    render(
      <MaterialBatchPicker unit="kg" factoryId="F006" taskId={34} productionBatchId={1}
        forceIndependentQuantity value={[]} onChange={() => undefined} onOptionsLoaded={onOptionsLoaded} />,
    );
    fireEvent.press(screen.getByLabelText('展开/折叠领料批次选择'));
    await waitFor(() => expect(onOptionsLoaded).toHaveBeenCalledWith(options));
  });
});
