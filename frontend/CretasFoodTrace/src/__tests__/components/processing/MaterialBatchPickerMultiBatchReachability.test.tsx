/**
 * 生产缺陷回归：首道报工从单批切到 4/5 批后，逐批 TextInput 曾渲染在 maxHeight=320 的
 * 内层 ScrollView 里。选中行增高但 Android 保留旧 scroll offset，首批输入会被裁到
 * viewport 上方；同时首批原有 singleBatchQty 没迁移，canConfirm 永远卡在空值。
 *
 * 本测试走组件真实入口并钉住两个旧实现会红的变异对照：
 * 1. 把 TextInput 移回 material-batch-list-scroll，结构断言立即失败；
 * 2. 删除单批→多批的数量迁移，首批 value 断言立即失败。
 */
import React, { useState } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
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

function buildBatches(): MaterialBatch[] {
  return Array.from({ length: 5 }, (_, index) => ({
    id: `MT-LSM-${index + 1}`,
    batchNumber: `MT-LSM-${index + 1}`,
    factoryId: 'LIUSHANMEN',
    materialTypeId: 'MT-DUCK-LEG',
    materialName: `酱鸭腿原料 ${index + 1}`,
    inboundQuantity: 100,
    remainingQuantity: index === 4 ? 10 : 100,
    reservedQuantity: 0,
    usedQuantity: 0,
    unit: 'kg',
    quantityUnit: 'kg',
    unitPrice: 12,
    totalCost: 1200,
    supplierId: 'SUP-LSM',
    inboundDate: '2026-08-30',
    status: 'available',
    createdAt: '2026-08-30T00:00:00Z',
  }));
}

const Harness: React.FC<{ singleBatchQty: string }> = ({ singleBatchQty }) => {
  const [refs, setRefs] = useState<MaterialBatchRef[]>([]);
  return (
    <MaterialBatchPicker
      unit="kg"
      factoryId="LIUSHANMEN"
      value={refs}
      onChange={setRefs}
      singleBatchQty={singleBatchQty}
      required
    />
  );
};

async function renderExpanded(): Promise<void> {
  mockedApi.getBatchesByStatus.mockResolvedValue({
    success: true,
    data: buildBatches(),
    message: 'ok',
  } as never);

  render(<Harness singleBatchQty="6.5" />);
  fireEvent.press(screen.getByLabelText('展开/折叠领料批次选择'));
  await screen.findByText('MT-LSM-1');
}

function selectBatch(index: number): void {
  fireEvent.press(screen.getByLabelText(`选择批次 MT-LSM-${index}`));
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('MaterialBatchPicker 多批次用量输入可达性', () => {
  it('单批切到多批时保留首批投入量，且数量输入不再位于固定高度批次列表内', async () => {
    await renderExpanded();

    selectBatch(1);
    expect(screen.queryByTestId('material-batch-selected-quantity-section')).toBeNull();

    selectBatch(2);

    const list = await screen.findByTestId('material-batch-list-scroll');
    const quantitySection = screen.getByTestId('material-batch-selected-quantity-section');
    const firstInput = within(quantitySection).getByTestId('material-batch-qty-MT-LSM-1');
    const secondInput = within(quantitySection).getByTestId('material-batch-qty-MT-LSM-2');

    expect(firstInput).toHaveProp('value', '6.5');
    expect(secondInput).toHaveProp('value', '');
    expect(within(list).queryByTestId('material-batch-qty-MT-LSM-1')).toBeNull();
    expect(within(list).queryByTestId('material-batch-qty-MT-LSM-2')).toBeNull();
  });

  it('2→5 批时五个输入都在页面外层可滚动编辑区，空值与超量继续禁用确定', async () => {
    await renderExpanded();

    for (let index = 1; index <= 5; index += 1) selectBatch(index);

    const quantitySection = await screen.findByTestId('material-batch-selected-quantity-section');
    for (let index = 1; index <= 5; index += 1) {
      expect(within(quantitySection).getByTestId(`material-batch-qty-MT-LSM-${index}`)).toBeTruthy();
    }

    expect(screen.getByText('请为每批次填写用量')).toBeTruthy();

    for (let index = 2; index <= 4; index += 1) {
      fireEvent.changeText(screen.getByTestId(`material-batch-qty-MT-LSM-${index}`), '2');
    }
    fireEvent.changeText(screen.getByTestId('material-batch-qty-MT-LSM-5'), '11');

    await waitFor(() => {
      expect(screen.getByText('已超出本批可领量，请调小后再确定')).toBeTruthy();
      expect(screen.getByText('请为每批次填写用量')).toBeTruthy();
    });

    fireEvent.changeText(screen.getByTestId('material-batch-qty-MT-LSM-5'), '2');
    await waitFor(() => expect(screen.getByText('确定选择')).toBeTruthy());
  });
});
