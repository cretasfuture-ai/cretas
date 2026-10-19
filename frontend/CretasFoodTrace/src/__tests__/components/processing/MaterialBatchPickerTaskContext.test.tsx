import React from 'react';
import { render, fireEvent, waitFor, screen } from '@testing-library/react-native';
import MaterialBatchPicker from '../../../components/processing/MaterialBatchPicker';
import { yieldReportApi } from '../../../services/api/yieldReportApi';
import { materialBatchApiClient } from '../../../services/api/materialBatchApiClient';

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

const mockedMaterialBatchApi = materialBatchApiClient as jest.Mocked<typeof materialBatchApiClient>;

beforeEach(() => {
  jest.clearAllMocks();
});

describe('MaterialBatchPicker task context wiring', () => {
  it('uses the mounted task route when reporting context is present', async () => {
    (yieldReportApi.listTaskMaterialOptions as jest.Mock).mockResolvedValue({
      success: true,
      data: [{ id: 'MB-1', batchNumber: 'B-1', materialTypeId: 'MAT-1', materialName: '原料', currentQuantity: 2, quantityUnit: 'kg', warehouseId: 'WH-1' }],
    });
    render(<MaterialBatchPicker unit="kg" factoryId="F006" taskId={1989} productionBatchId={10944} value={[]} onChange={() => undefined} />);
    fireEvent.press(screen.getByLabelText('展开/折叠领料批次选择'));
    await waitFor(() => expect(yieldReportApi.listTaskMaterialOptions).toHaveBeenCalledWith(10944, 1989, 'F006'));
    expect(mockedMaterialBatchApi.getBatchesByStatus).not.toHaveBeenCalled();
    expect(mockedMaterialBatchApi.getBatchById).not.toHaveBeenCalled();
    expect(mockedMaterialBatchApi.getFefoBatches).not.toHaveBeenCalled();
  });

  it('requires an independent positive quantity before confirming a single planned-null task candidate', async () => {
    const onChange = jest.fn();
    (yieldReportApi.listTaskMaterialOptions as jest.Mock).mockResolvedValue({
      success: true,
      data: [{ id: 'MB-1', batchNumber: 'B-1', materialTypeId: 'MAT-1', materialName: '原料', currentQuantity: 2, quantityUnit: 'kg', warehouseId: 'WH-1' }],
    });
    render(
      <MaterialBatchPicker
        unit="kg"
        factoryId="F006"
        taskId={1990}
        productionBatchId={10944}
        forceIndependentQuantity
        value={[]}
        onChange={onChange}
      />,
    );
    fireEvent.press(screen.getByLabelText('展开/折叠领料批次选择'));
    await waitFor(() => expect(screen.getByTestId('material-batch-qty-MB-1')).toBeTruthy());
    expect(onChange).not.toHaveBeenCalledWith(expect.arrayContaining([expect.objectContaining({ materialBatchId: 'MB-1' })]));

    fireEvent.changeText(screen.getByTestId('material-batch-qty-MB-1'), '0.1');
    fireEvent.press(screen.getByTestId('material-batch-confirm'));
    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith([
      expect.objectContaining({ materialBatchId: 'MB-1', quantity: 0.1, unit: 'kg' }),
    ]));
    expect(mockedMaterialBatchApi.getBatchesByStatus).not.toHaveBeenCalled();
    expect(mockedMaterialBatchApi.getBatchById).not.toHaveBeenCalled();
    expect(mockedMaterialBatchApi.getFefoBatches).not.toHaveBeenCalled();
  });

  it('auto allocates only mounted task candidates and never calls generic FEFO', async () => {
    (yieldReportApi.listTaskMaterialOptions as jest.Mock).mockResolvedValue({
      success: true,
      data: [
        { id: 'MB-1', batchNumber: 'B-1', materialTypeId: 'MAT-1', materialName: '原料', currentQuantity: 5, quantityUnit: 'kg', warehouseId: 'WH-1' },
        { id: 'MB-2', batchNumber: 'B-2', materialTypeId: 'MAT-1', materialName: '原料', currentQuantity: 4, quantityUnit: 'kg', warehouseId: 'WH-1' },
      ],
    });
    render(<MaterialBatchPicker unit="kg" factoryId="F006" taskId={1990} productionBatchId={10944} singleBatchQty="9" value={[]} onChange={() => undefined} />);
    fireEvent.press(screen.getByLabelText('展开/折叠领料批次选择'));
    await waitFor(() => expect(screen.getByLabelText('选择批次 B-1')).toBeTruthy());
    fireEvent.press(screen.getByLabelText('选择批次 B-1'));
    fireEvent.press(await screen.findByTestId('material-batch-allocation-option-auto'));
    await waitFor(() => expect(screen.getByTestId('material-batch-auto-success')).toBeTruthy());
    expect(screen.getByTestId('material-batch-qty-MB-1').props.value).toBe('5');
    expect(screen.getByTestId('material-batch-qty-MB-2').props.value).toBe('4');
    expect(mockedMaterialBatchApi.getBatchesByStatus).not.toHaveBeenCalled();
    expect(mockedMaterialBatchApi.getBatchById).not.toHaveBeenCalled();
    expect(mockedMaterialBatchApi.getFefoBatches).not.toHaveBeenCalled();
  });

  it('fails loudly when only one task context field is present', async () => {
    render(<MaterialBatchPicker unit="kg" factoryId="F006" taskId={1989} value={[]} onChange={() => undefined} />);
    fireEvent.press(screen.getByLabelText('展开/折叠领料批次选择'));
    await waitFor(() => expect(screen.getByText('当前报工任务上下文不完整，请返回任务列表后重试')).toBeTruthy());
    expect(yieldReportApi.listTaskMaterialOptions).not.toHaveBeenCalled();
  });
});
