import React, { useState } from 'react';
import { Text } from 'react-native';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import MaterialBatchPicker, {
  allocateQuantityAcrossBatches,
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
      getFefoBatches: jest.fn(),
    },
  };
});

const mockedApi = materialBatchApiClient as jest.Mocked<typeof materialBatchApiClient>;

function buildBatches(
  remaining: number[] = [5, 4, 3, 2, 10],
  quantityUnit = 'kg',
): MaterialBatch[] {
  return remaining.map((remainingQuantity, index) => ({
    id: `AUTO-${index + 1}`,
    batchNumber: `AUTO-${index + 1}`,
    factoryId: 'LIUSHANMEN',
    materialTypeId: 'MT-DUCK-LEG',
    materialName: '酱鸭腿原料',
    inboundQuantity: 100,
    remainingQuantity,
    reservedQuantity: 0,
    usedQuantity: 0,
    unit: 'kg',
    quantityUnit,
    unitPrice: 12,
    totalCost: 1200,
    supplierId: 'SUP-LSM',
    inboundDate: `2026-08-${20 + index}`,
    expiryDate: `2026-09-${10 + index}`,
    status: 'available',
    createdAt: '2026-08-30T00:00:00Z',
  }));
}

const Harness: React.FC<{ target: string }> = ({ target }) => {
  const [refs, setRefs] = useState<MaterialBatchRef[]>([]);
  return (
    <>
      <MaterialBatchPicker
        unit="kg"
        factoryId="LIUSHANMEN"
        value={refs}
        onChange={setRefs}
        singleBatchQty={target}
        required
      />
      <Text testID="material-batch-refs-json">{JSON.stringify(refs)}</Text>
    </>
  );
};

async function renderExpanded(target: string, batches = buildBatches()): Promise<MaterialBatch[]> {
  mockedApi.getBatchesByStatus.mockResolvedValue({
    success: true,
    data: batches,
    message: 'ok',
  } as never);
  render(<Harness target={target} />);
  fireEvent.press(screen.getByLabelText('展开/折叠领料批次选择'));
  await screen.findByText('AUTO-1');
  return batches;
}

function selectBatch(index: number): void {
  fireEvent.press(screen.getByLabelText(`选择批次 AUTO-${index}`));
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('MaterialBatchPicker 自动分配用量', () => {
  it('纯分配逻辑按推荐顺序逐批封顶，最后一批只取尚缺量', () => {
    expect(
      allocateQuantityAcrossBatches(
        [
          { id: 'B1', remainingQuantity: 5 },
          { id: 'B2', remainingQuantity: 4 },
          { id: 'B3', remainingQuantity: 10 },
        ],
        12,
      ),
    ).toEqual({
      allocations: [
        { batchId: 'B1', quantity: 5 },
        { batchId: 'B2', quantity: 4 },
        { batchId: 'B3', quantity: 3 },
      ],
      allocatedTotal: 12,
      shortage: 0,
    });
  });

  it('单批不足时在同一汇总窗口优先给自动方案，也保留手动方案', async () => {
    await renderExpanded('9');
    selectBatch(1);

    const window = await screen.findByTestId('material-batch-allocation-window');
    expect(within(window).getByTestId('material-batch-allocation-option-auto')).toBeTruthy();
    expect(within(window).getByText('推荐')).toBeTruthy();
    expect(within(window).getByTestId('material-batch-allocation-option-manual')).toBeTruthy();
    expect(within(window).getByText(/当前批次最多可领 5 kg/)).toBeTruthy();
  });

  it('自动方案按 FEFO 返回拆成两批并逐批保留身份与数量', async () => {
    const batches = await renderExpanded('9');
    mockedApi.getFefoBatches.mockResolvedValue({
      success: true,
      data: batches.slice(0, 2),
      message: 'ok',
    } as never);
    selectBatch(1);
    fireEvent.press(await screen.findByTestId('material-batch-allocation-option-auto'));

    await screen.findByTestId('material-batch-auto-success');
    expect(mockedApi.getFefoBatches).toHaveBeenCalledWith('MT-DUCK-LEG', 9, 'LIUSHANMEN');
    expect(screen.getByTestId('material-batch-qty-AUTO-1')).toHaveProp('value', '5');
    expect(screen.getByTestId('material-batch-qty-AUTO-2')).toHaveProp('value', '4');
    expect(screen.getByText('自动分配结果（逐批核对）')).toBeTruthy();
    expect(screen.getByTestId('material-batch-confirm')).toHaveProp('disabled', false);

    await waitFor(() => {
      expect(screen.getByTestId('material-batch-refs-json').props.children).toContain(
        '"materialBatchId":"AUTO-1","quantity":5',
      );
      expect(screen.getByTestId('material-batch-refs-json').props.children).toContain(
        '"materialBatchId":"AUTO-2","quantity":4',
      );
    });
  });

  it('FEFO 另一个单批已足量时仍使用自动结果数量，不回退到原选批次', async () => {
    const batches = await renderExpanded('9');
    mockedApi.getFefoBatches.mockResolvedValue({
      success: true,
      data: [batches[4]],
      message: 'ok',
    } as never);
    selectBatch(1);
    fireEvent.press(await screen.findByTestId('material-batch-allocation-option-auto'));

    await screen.findByTestId('material-batch-auto-success');
    expect(screen.getByTestId('material-batch-qty-AUTO-5')).toHaveProp('value', '9');
    expect(screen.getByTestId('material-batch-confirm')).toHaveProp('disabled', false);
    expect(screen.getByLabelText('选择批次 AUTO-1')).toBeTruthy();
    await waitFor(() => {
      expect(screen.getByTestId('material-batch-refs-json').props.children).toContain(
        '"materialBatchId":"AUTO-5","quantity":9',
      );
      expect(screen.getByTestId('material-batch-refs-json').props.children).not.toContain('AUTO-1');
    });
  });

  it('自动方案最多可生成五个仍位于外层编辑区的批次输入', async () => {
    const batches = buildBatches([3, 3, 3, 3, 10]);
    await renderExpanded('15', batches);
    mockedApi.getFefoBatches.mockResolvedValue({
      success: true,
      data: batches,
      message: 'ok',
    } as never);
    selectBatch(1);
    fireEvent.press(await screen.findByTestId('material-batch-allocation-option-auto'));

    const quantitySection = await screen.findByTestId('material-batch-selected-quantity-section');
    for (let index = 1; index <= 5; index += 1) {
      expect(within(quantitySection).getByTestId(`material-batch-qty-AUTO-${index}`)).toBeTruthy();
    }
    expect(screen.getByTestId('material-batch-qty-AUTO-5')).toHaveProp('value', '3');
    expect(screen.getByTestId('material-batch-confirm')).toHaveProp('disabled', false);
  });

  it('手动方案保留原首批投入量，并继续使用逐批可达输入', async () => {
    await renderExpanded('9');
    selectBatch(1);
    fireEvent.press(await screen.findByTestId('material-batch-allocation-option-manual'));

    expect(screen.getByText(/手动模式：继续在上方勾选批次/)).toBeTruthy();
    selectBatch(2);
    expect(screen.getByTestId('material-batch-qty-AUTO-1')).toHaveProp('value', '9');
    expect(screen.getByTestId('material-batch-qty-AUTO-2')).toHaveProp('value', '');

    fireEvent.changeText(screen.getByTestId('material-batch-qty-AUTO-1'), '5');
    fireEvent.changeText(screen.getByTestId('material-batch-qty-AUTO-2'), '4');
    await waitFor(() => expect(screen.getByTestId('material-batch-confirm')).toHaveProp('disabled', false));
    expect(mockedApi.getFefoBatches).not.toHaveBeenCalled();
  });

  it('自动库存不足时保留已分配结果、阻塞确认，并可原地切回手动', async () => {
    const batches = await renderExpanded('12');
    mockedApi.getFefoBatches.mockResolvedValue({
      success: true,
      data: batches.slice(0, 2),
      message: 'ok',
    } as never);
    selectBatch(1);
    fireEvent.press(await screen.findByTestId('material-batch-allocation-option-auto'));

    expect(await screen.findByText(/还缺 3 kg/)).toBeTruthy();
    expect(screen.getByTestId('material-batch-qty-AUTO-1')).toHaveProp('value', '5');
    expect(screen.getByTestId('material-batch-qty-AUTO-2')).toHaveProp('value', '4');
    expect(screen.getByTestId('material-batch-confirm')).toHaveProp('disabled', true);

    fireEvent.press(screen.getByTestId('material-batch-allocation-switch-manual'));
    expect(screen.getByText(/手动模式：继续在上方勾选批次/)).toBeTruthy();
    expect(screen.getByTestId('material-batch-confirm')).toHaveProp('disabled', false);
  });

  it('单位不一致时不猜换算，错误仍与手动入口留在同一窗口', async () => {
    const batches = await renderExpanded('9');
    mockedApi.getFefoBatches.mockResolvedValue({
      success: true,
      data: [batches[0], { ...batches[1], quantityUnit: 'box' }],
      message: 'ok',
    } as never);
    selectBatch(1);
    fireEvent.press(await screen.findByTestId('material-batch-allocation-option-auto'));

    expect(await screen.findByText(/与投入单位 kg 不一致/)).toBeTruthy();
    expect(screen.getByTestId('material-batch-allocation-option-manual')).toBeTruthy();
    expect(screen.queryByTestId('material-batch-selected-quantity-section')).toBeNull();
  });

  it('自动接口失败不清空当前选择，并可切到手动路径', async () => {
    await renderExpanded('9');
    mockedApi.getFefoBatches.mockRejectedValue(new Error('网络暂不可用'));
    selectBatch(1);
    fireEvent.press(await screen.findByTestId('material-batch-allocation-option-auto'));

    expect(await screen.findByText(/网络暂不可用/)).toBeTruthy();
    expect(screen.getByLabelText('取消选择批次 AUTO-1')).toBeTruthy();
    fireEvent.press(screen.getByTestId('material-batch-allocation-option-manual'));
    expect(screen.getByText(/手动模式：继续在上方勾选批次/)).toBeTruthy();
  });
});
