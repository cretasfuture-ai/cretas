/**
 * 客户 / 供应商 / 报废审批 —— 无权限的【操作按钮】置灰而不是隐藏。
 *
 * 三个屏原来的写法都是 `{canWrite && (<操作区>)}` / `{canApprove && (<审批>)}`,
 * 只读角色看到的是一张【没有任何按钮】的卡, 分不清「不能改」和「系统不支持改」。
 *
 * ⛔ 断言落在那个按钮上(在不在 / disabled / onPress / 弹窗文案),
 *    并且每组都带一条【有权限时照常可用】的阴性对照 ——
 *    否则「全都灰了」也会让上面那几条全绿。
 */
import React from 'react';
import { Alert, StyleSheet } from 'react-native';
import { render, fireEvent, waitFor } from '@testing-library/react-native';
import CustomerManagementScreen, {
  CUSTOMER_WRITE_REASON,
} from '../../../screens/management/CustomerManagementScreen';
import SupplierManagementScreen, {
  SUPPLIER_WRITE_REASON,
} from '../../../screens/management/SupplierManagementScreen';
import DisposalRecordManagementScreen, {
  DISPOSAL_APPROVE_REASON,
} from '../../../screens/management/DisposalRecordManagementScreen';
import { customerApiClient } from '../../../services/api/customerApiClient';
import { supplierApiClient } from '../../../services/api/supplierApiClient';
import { disposalRecordApiClient } from '../../../services/api/disposalRecordApiClient';
import { useAuthStore } from '../../../store/authStore';
import { PERMISSION_LOCKED_TITLE } from '../../../components/permission/PermissionGatedAction';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

jest.mock('../../../store/authStore', () => ({ useAuthStore: jest.fn() }));

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn() }),
}));

jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return { SafeAreaView: ({ children, ...p }: any) => <View {...p}>{children}</View> };
});

jest.mock('../../../services/api/customerApiClient', () => ({
  customerApiClient: {
    getCustomers: jest.fn(),
    searchCustomers: jest.fn(),
    createCustomer: jest.fn(),
    updateCustomer: jest.fn(),
    deleteCustomer: jest.fn(),
    toggleCustomerStatus: jest.fn(),
  },
}));
jest.mock('../../../services/api/supplierApiClient', () => ({
  supplierApiClient: {
    getSuppliers: jest.fn(),
    searchSuppliers: jest.fn(),
    createSupplier: jest.fn(),
    updateSupplier: jest.fn(),
    deleteSupplier: jest.fn(),
    toggleSupplierStatus: jest.fn(),
  },
}));
jest.mock('../../../services/api/disposalRecordApiClient', () => ({
  disposalRecordApiClient: {
    getDisposalRecords: jest.fn(),
    createDisposalRecord: jest.fn(),
    updateDisposalRecord: jest.fn(),
    deleteDisposalRecord: jest.fn(),
    approveDisposalRecord: jest.fn(),
  },
}));

jest.mock('../../../utils/errorHandler', () => ({
  handleError: jest.fn(),
  getErrorMsg: jest.fn(() => 'err'),
}));

const CUSTOMER = { id: 'c1', name: '张三食品', customerCode: 'C001', isActive: true };
const SUPPLIER = { id: 's1', name: '李四水产', supplierCode: 'S001', isActive: true };
const DISPOSAL = {
  id: 'd1',
  disposalType: 'raw_material',
  disposalQuantity: 5,
  quantityUnit: 'kg',
  disposalReason: '变质',
  disposalDate: '2026-08-26',
  isApproved: false,
};

function setRole(role: string, userType: 'factory' | 'platform' = 'factory') {
  (useAuthStore as unknown as jest.Mock).mockReturnValue({
    user: { userType, factoryId: 'F1', factoryUser: { role }, roleCode: role },
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  (customerApiClient.getCustomers as jest.Mock).mockResolvedValue({ data: [CUSTOMER] });
  (supplierApiClient.getSuppliers as jest.Mock).mockResolvedValue({ data: [SUPPLIER] });
  (disposalRecordApiClient.getDisposalRecords as jest.Mock).mockResolvedValue({ data: [DISPOSAL] });
});

describe('CustomerManagementScreen —— 只读角色', () => {
  // operator 在 PERMISSION_MATRIX 里对 sales/finance 都不是 read_write
  beforeEach(() => setRole('operator'));

  it('编辑按钮【仍然渲染】, 但是 disabled 且 onPress 已摘掉', async () => {
    const { getByTestId } = render(<CustomerManagementScreen />);
    const btn = await waitFor(() => getByTestId(`customer-edit-btn-${CUSTOMER.id}`));

    expect(btn.props.disabled).toBe(true);
    expect(btn.props.onPress).toBeUndefined();
  });

  it('新增 FAB 也是置灰而不是消失', async () => {
    const { getByTestId } = render(<CustomerManagementScreen />);
    const fab = await waitFor(() => getByTestId('customer-create-fab'));

    expect(fab.props.disabled).toBe(true);
  });

  it('点操作区会说清「这项归谁」, 且不调后端', async () => {
    const { getByTestId } = render(<CustomerManagementScreen />);
    const gate = await waitFor(() => getByTestId(`customer-actions-gate-${CUSTOMER.id}`));

    fireEvent.press(gate);

    expect(Alert.alert).toHaveBeenCalledWith(PERMISSION_LOCKED_TITLE, CUSTOMER_WRITE_REASON);
    expect(customerApiClient.updateCustomer).not.toHaveBeenCalled();
    expect(customerApiClient.deleteCustomer).not.toHaveBeenCalled();
    expect(customerApiClient.toggleCustomerStatus).not.toHaveBeenCalled();
  });

  // 这条守的是一个【只在没权限的人屏幕上出现】的错位: 置灰时内容多套一层 View,
  // 若 flexDirection 留在外层, 三个按钮会从一排变成竖着堆。自测永远看不到。
  it('置灰状态下那一排按钮仍然是【一排】—— 排列样式必须和置灰落在同一层', async () => {
    const { getByTestId } = render(<CustomerManagementScreen />);
    const btn = await waitFor(() => getByTestId(`customer-edit-btn-${CUSTOMER.id}`));

    // 往上找到【被置灰的那一层】(opacity 0.45), 断言排列样式就在它身上。
    // ⚠️ 不能只看 btn.parent —— 那是 paper 替身的复合组件, 不是宿主 View。
    let layer: any = btn.parent;
    let flat: any = null;
    while (layer) {
      const s = StyleSheet.flatten(layer.props?.style) as any;
      if (s && s.opacity === 0.45) {
        flat = s;
        break;
      }
      layer = layer.parent;
    }

    expect(flat).not.toBeNull();
    expect(flat.flexDirection).toBe('row');
  });

  it('阴性对照: 有写权(销售经理)时按钮可用、handler 还在', async () => {
    setRole('sales_manager');
    const { getByTestId } = render(<CustomerManagementScreen />);
    const btn = await waitFor(() => getByTestId(`customer-edit-btn-${CUSTOMER.id}`));

    expect(btn.props.disabled).toBeFalsy();
    expect(typeof btn.props.onPress).toBe('function');
  });
});

describe('SupplierManagementScreen —— 只读角色', () => {
  beforeEach(() => setRole('operator'));

  it('编辑按钮置灰但可见', async () => {
    const { getByTestId } = render(<SupplierManagementScreen />);
    const btn = await waitFor(() => getByTestId(`supplier-edit-btn-${SUPPLIER.id}`));

    expect(btn.props.disabled).toBe(true);
    expect(btn.props.onPress).toBeUndefined();
  });

  it('点操作区说清归谁, 且不调后端', async () => {
    const { getByTestId } = render(<SupplierManagementScreen />);
    const gate = await waitFor(() => getByTestId(`supplier-actions-gate-${SUPPLIER.id}`));

    fireEvent.press(gate);

    expect(Alert.alert).toHaveBeenCalledWith(PERMISSION_LOCKED_TITLE, SUPPLIER_WRITE_REASON);
    expect(supplierApiClient.deleteSupplier).not.toHaveBeenCalled();
  });

  it('阴性对照: 采购经理照常可用', async () => {
    setRole('procurement_manager');
    const { getByTestId } = render(<SupplierManagementScreen />);
    const btn = await waitFor(() => getByTestId(`supplier-edit-btn-${SUPPLIER.id}`));

    expect(btn.props.disabled).toBeFalsy();
  });
});

describe('DisposalRecordManagementScreen —— 能编辑但不能审批的角色', () => {
  // department_admin: canManage=true(进得来、能编辑), canApprove=false
  beforeEach(() => setRole('department_admin'));

  it('审批按钮【仍然渲染】, disabled 且 onPress 已摘掉', async () => {
    const { getByTestId } = render(<DisposalRecordManagementScreen />);
    const btn = await waitFor(() => getByTestId(`disposal-approve-btn-${DISPOSAL.id}`));

    expect(btn.props.disabled).toBe(true);
    expect(btn.props.onPress).toBeUndefined();
  });

  it('点它说清审批归谁, 且不调审批接口', async () => {
    const { getByTestId } = render(<DisposalRecordManagementScreen />);
    const gate = await waitFor(() => getByTestId(`disposal-approve-gate-${DISPOSAL.id}`));

    fireEvent.press(gate);

    expect(Alert.alert).toHaveBeenCalledWith(PERMISSION_LOCKED_TITLE, DISPOSAL_APPROVE_REASON);
    expect(disposalRecordApiClient.approveDisposalRecord).not.toHaveBeenCalled();
  });

  it('阴性对照: 同一屏、同一张卡上的【编辑】按钮没有被一起灰掉', async () => {
    const { getByTestId } = render(<DisposalRecordManagementScreen />);
    const approve = await waitFor(() => getByTestId(`disposal-approve-btn-${DISPOSAL.id}`));
    const edit = getByTestId(`disposal-edit-btn-${DISPOSAL.id}`);

    // 灰的只有审批那一个, 不是整排
    expect(approve.props.disabled).toBe(true);
    expect(edit.props.disabled).toBeFalsy();
    expect(typeof edit.props.onPress).toBe('function');
  });

  it('阴性对照: 工厂超管的审批按钮可用、handler 还在', async () => {
    setRole('factory_super_admin');
    const { getByTestId } = render(<DisposalRecordManagementScreen />);
    const btn = await waitFor(() => getByTestId(`disposal-approve-btn-${DISPOSAL.id}`));

    expect(btn.props.disabled).toBeFalsy();
    expect(typeof btn.props.onPress).toBe('function');
  });
});
