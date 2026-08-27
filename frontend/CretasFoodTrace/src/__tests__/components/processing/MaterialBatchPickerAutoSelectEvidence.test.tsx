/**
 * P6 —— 单批次自动选中之后，屏上没有任何「选上了」的证据。
 *
 * <h3>缺陷长相 (2026-08-26 用本文件的用例复现)</h3>
 * 只有一个可用批次时 `loadBatches` 会自动选中它并自动收起 (F6, 不让操作工多点一次)。
 * 收起之后唯一的证据就是那行摘要 `已选 X · 用量 N kg`，而摘要的渲染条件是
 * `selectedCount > 0`，`selectedCount` 又取自**父层**的 `value.length`：
 *
 * ```
 * loadBatches  → setRows([selected])  +  onChange([{ ..., quantity: 0 }])   // 占位
 * rows 变了 → :184 的 effect 重算 refs → 单批次取 parseFloat(singleBatchQty)
 *            → 投入量还没填 → NaN → refs = []  → onChange([])               // 父层清零
 * ```
 *
 * 于是在「自动选中了、但投入量还没填」这段时间里，父层的 `value` 是空的，
 * header 退回带红星的原样，摘要行一个字都没有 —— 操作工看到的和「没选上」一模一样。
 *
 * <h3>口径</h3>
 * 断言跑在**组件真实入口**上 (渲染 MaterialBatchPicker 本体 + 受控父层, 只桩 API)。
 * 阳性对照放在同一个文件里: 同样的单批次、把投入量填上 → 摘要必须出现。
 * 没有那条对照，「摘要没出现」既可能是缺陷、也可能是我的 harness 根本渲染不出摘要。
 */
import React, { useState } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import MaterialBatchPicker, {
  MaterialBatchRef,
} from '../../../components/processing/MaterialBatchPicker';
import { materialBatchApiClient, MaterialBatch } from '../../../services/api/materialBatchApiClient';

jest.mock('../../../services/api/materialBatchApiClient', () => {
  const actual = jest.requireActual('../../../services/api/materialBatchApiClient');
  return {
    ...actual,
    materialBatchApiClient: {
      getBatchesByStatus: jest.fn(),
      getBatchById: jest.fn(),
    },
  };
});

const mockedApi = materialBatchApiClient as jest.Mocked<typeof materialBatchApiClient>;

const ONLY_BATCH: MaterialBatch = {
  id: 'MT-20260826-0001',
  batchNumber: 'MT-20260826-0001',
  factoryId: 'F006',
  materialTypeId: 'MT-TYPE-1',
  materialName: '鲈鱼原料',
  inboundQuantity: 200,
  remainingQuantity: 120,
  reservedQuantity: 0,
  usedQuantity: 0,
  unit: 'kg',
  quantityUnit: 'kg',
  unitPrice: 3.5,
  totalCost: 700,
  supplierId: 'SUP-1',
  inboundDate: '2026-08-26',
  status: 'available',
  createdAt: '2026-08-26T00:00:00Z',
};

/**
 * 受控父层 —— 复刻 YieldStepReportScreen 的接线:
 * `value` 是父层 state, 由 picker 的 onChange 驱动; `singleBatchQty` 是屏幕的「投入量」。
 */
const Harness: React.FC<{ inputQty?: string }> = ({ inputQty = '' }) => {
  const [refs, setRefs] = useState<MaterialBatchRef[]>([]);
  return (
    <MaterialBatchPicker
      unit="kg"
      factoryId="F006"
      value={refs}
      onChange={setRefs}
      singleBatchQty={inputQty}
      required
    />
  );
};

/** 点开 header 触发懒加载 (picker 只在展开过一次之后才拉批次)。 */
async function openAndLoad(): Promise<void> {
  fireEvent.press(screen.getByLabelText('展开/折叠领料批次选择'));
  await waitFor(() => expect(mockedApi.getBatchesByStatus).toHaveBeenCalled());
}

beforeEach(() => {
  jest.clearAllMocks();
  mockedApi.getBatchesByStatus.mockResolvedValue({
    success: true,
    data: [ONLY_BATCH],
    message: 'ok',
  } as never);
});

describe('P6 单批次自动选中后必须留下「选上了」的证据', () => {
  it('阳性对照: 投入量已填时摘要行本来就出得来 (证明 harness 渲染得出摘要)', async () => {
    render(<Harness inputQty="12" />);
    await openAndLoad();

    const summary = await screen.findByTestId('material-batch-collapsed-summary');
    expect(summary).toHaveTextContent(/鲈鱼原料/);
    expect(summary).toHaveTextContent(/用量 12 kg/);
  });

  it('投入量还没填时也必须有摘要行 —— 不能和「没选上」长得一样', async () => {
    render(<Harness inputQty="" />);
    await openAndLoad();

    // 自动选中 + 自动收起之后, 屏上必须能读到「已选了哪一批」
    const summary = await screen.findByTestId('material-batch-collapsed-summary');
    expect(summary).toHaveTextContent(/鲈鱼原料/);
    // 用量还没填要如实说, ⛔ 不许印一个 0 冒充读数
    expect(summary).toHaveTextContent(/用量待填/);
    expect(summary).not.toHaveTextContent(/用量 0 /);
  });

  it('自动选中之后 header 上的红星要换成已选徽标 —— 红星意味着「还没选」', async () => {
    render(<Harness inputQty="" />);
    await openAndLoad();

    await waitFor(() =>
      expect(screen.getByTestId('material-batch-header-badge')).toHaveTextContent(/已选 1 批/),
    );
    expect(screen.queryByTestId('material-batch-required-mark')).toBeNull();
  });

  it('阴性对照: 一个批次都没有时, 红星还在、摘要不出现', async () => {
    mockedApi.getBatchesByStatus.mockResolvedValue({
      success: true,
      data: [],
      message: 'ok',
    } as never);
    render(<Harness inputQty="" />);
    await openAndLoad();

    await waitFor(() => expect(screen.getByText('暂无可用原料批次')).toBeTruthy());
    expect(screen.getByTestId('material-batch-required-mark')).toBeTruthy();
    expect(screen.queryByTestId('material-batch-collapsed-summary')).toBeNull();
  });
});
