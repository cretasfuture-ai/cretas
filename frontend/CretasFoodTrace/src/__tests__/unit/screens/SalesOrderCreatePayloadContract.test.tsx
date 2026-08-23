/**
 * App 端建的销售订单，行项目上<b>缺规格、缺箱数</b>；税率留空会被静默算成 0%。
 *
 * <h3>prod 实测（2026-08-19，cretas_prod_db / public schema，只读）</h3>
 * <pre>
 * sales_orders        38 行，default_tax_rate 非空  0 行
 * sales_order_items  167 行，specification 27 / box_quantity 25 / tax_rate 41
 * customers           29 行，default_tax_rate 非空  7 行（22 行没配 ⇒ 留空 = 0%）
 * </pre>
 *
 * <h3>为什么必须渲染真屏，而不是只测 utils</h3>
 * 这个缺陷的形状是<b>「字段没进 payload」</b> —— utils 全绿、tsc 全绿，
 * 而 `handleSubmit` 里少写一行就照样漏。本仓已有多次「单测+tsc 全绿而界面是坏的」记录。
 * ⇒ 断言跑在产品真实入口：渲染 `SalesOrderCreateScreen` 本体，只桩掉 API 与 store，
 * 直接读 `salesApiClient.createOrder` 收到的那个对象。
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn(), setOptions: jest.fn(), addListener: jest.fn() }),
  useRoute: () => ({ params: {} }),
}));

jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return { SafeAreaView: ({ children, ...props }: any) => <View {...props}>{children}</View> };
});

// react-native-paper 在这套自定义 react-native mock 下跑不起来 (同 YieldStepReportScreen.test.tsx)。
// 这些都是纯展示容器, 换成最小实现不影响本缺陷要验证的 payload。
jest.mock('react-native-paper', () => {
  const { View, Text, TextInput, TouchableOpacity } = require('react-native');
  const Appbar: any = ({ children }: any) => <View>{children}</View>;
  Appbar.Header = ({ children }: any) => <View>{children}</View>;
  Appbar.BackAction = () => <View />;
  Appbar.Content = ({ title }: any) => <Text>{title}</Text>;
  Appbar.Action = ({ onPress, testID, disabled }: any) => (
    <TouchableOpacity onPress={onPress} testID={testID} disabled={disabled} />
  );
  const Card: any = ({ children, testID }: any) => <View testID={testID}>{children}</View>;
  Card.Content = ({ children }: any) => <View>{children}</View>;
  const Menu: any = ({ children, anchor }: any) => <View>{anchor}{children}</View>;
  Menu.Item = ({ title, onPress }: any) => <TouchableOpacity onPress={onPress}><Text>{title}</Text></TouchableOpacity>;
  return {
    ActivityIndicator: () => <View />,
    Appbar,
    Button: ({ children, onPress, testID, disabled }: any) => (
      <TouchableOpacity onPress={onPress} testID={testID} disabled={disabled}><Text>{children}</Text></TouchableOpacity>
    ),
    Card,
    IconButton: ({ onPress, testID }: any) => <TouchableOpacity onPress={onPress} testID={testID} />,
    Menu,
    Text: ({ children, testID }: any) => <Text testID={testID}>{children}</Text>,
    TextInput: ({ value, onChangeText, testID, placeholder }: any) => (
      <TextInput value={value} onChangeText={onChangeText} testID={testID} placeholder={placeholder} />
    ),
  };
});

// 真实的 SearchableDropdown 有搜索框/弹层, 与本缺陷无关。换成一个按 testID 直接触发
// onSelect 的最小实现, 让测试能确定性地"选中"客户与产品。
jest.mock('../../../components/report/SearchableDropdown', () => {
  const { TouchableOpacity, Text } = require('react-native');
  return {
    __esModule: true,
    default: ({ options, onSelect, testID }: any) => (
      <>
        {options.map((option: any) => (
          <TouchableOpacity
            key={option.value}
            testID={`${testID}-option-${option.value}`}
            onPress={() => onSelect(option.value)}
          >
            <Text>{option.label}</Text>
          </TouchableOpacity>
        ))}
      </>
    ),
  };
});

const createOrder = jest.fn();
const getActiveCustomers = jest.fn();
const getActiveProductTypes = jest.fn();
const getPackagingSpecs = jest.fn();

jest.mock('../../../services/api/salesApiClient', () => ({
  salesApiClient: { createOrder: (...args: any[]) => createOrder(...args) },
}));
jest.mock('../../../services/api/customerApiClient', () => ({
  customerApiClient: { getActiveCustomers: (...args: any[]) => getActiveCustomers(...args) },
}));
jest.mock('../../../services/api/productTypeApiClient', () => ({
  productTypeApiClient: {
    getActiveProductTypes: (...args: any[]) => getActiveProductTypes(...args),
    getPackagingSpecs: (...args: any[]) => getPackagingSpecs(...args),
  },
}));

jest.mock('../../../store/authStore', () => ({
  useAuthStore: () => ({ user: { id: 42, username: 'zhaosi', fullName: '赵四', factoryId: 'F001' } }),
}));

import { Alert } from 'react-native';
import SalesOrderCreateScreen from '../../../screens/factory-admin/inventory/SalesOrderCreateScreen';

const CUSTOMER_WITH_DEFAULT = {
  id: 'c-taxed', factoryId: 'F001', customerCode: 'C001', code: 'C001', name: '有默认税率的客户',
  isActive: true, createdAt: '2026-01-01', defaultTaxRate: 13,
};
const CUSTOMER_WITHOUT_DEFAULT = {
  id: 'c-plain', factoryId: 'F001', customerCode: 'C002', code: 'C002', name: '没配默认税率的客户',
  isActive: true, createdAt: '2026-01-01',
};

const PRODUCT = {
  id: 'p-1', factoryId: 'F001', productCode: 'SKU-1', name: '卤猪蹄',
  unit: '盒', unitPrice: 25, isActive: true, createdAt: '2026-01-01',
  specification: '310g*42袋/箱',
  packageSpec: '整箱',
  boxConversionCoefficient: 12,
};

const PACKAGING_SPECS = [
  { id: 'spec-12', name: '12盒/箱', packageUnit: '箱', baseUnit: '盒', conversionFactor: 12, defaultSpec: true, active: true, sortOrder: 1 },
];

beforeEach(() => {
  jest.clearAllMocks();
  getActiveCustomers.mockResolvedValue([CUSTOMER_WITH_DEFAULT, CUSTOMER_WITHOUT_DEFAULT]);
  getActiveProductTypes.mockResolvedValue([PRODUCT]);
  getPackagingSpecs.mockResolvedValue(PACKAGING_SPECS);
  createOrder.mockResolvedValue({ success: true, data: { id: 'so-1', orderNumber: 'SO-1' } });
});

/** 建一张单：选客户 → 选产品 → 填数量/单价 → (可选) 填税率 → 提交。 */
async function fillAndSubmit(options: { customerId: string; quantity?: string; taxRate?: string }) {
  render(<SalesOrderCreateScreen />);

  await waitFor(() => expect(screen.getByTestId(`sales-create-customer-option-${options.customerId}`)).toBeTruthy());
  fireEvent.press(screen.getByTestId(`sales-create-customer-option-${options.customerId}`));
  fireEvent.press(screen.getByTestId('sales-create-product-0-option-p-1'));

  // 选完产品会异步拉箱规, 等它落进 state 再继续。
  await waitFor(() => expect(getPackagingSpecs).toHaveBeenCalled());

  fireEvent.changeText(screen.getByTestId('sales-create-quantity-0'), options.quantity ?? '24');
  fireEvent.changeText(screen.getByTestId('sales-create-unit-price-0'), '25');
  if (options.taxRate !== undefined) {
    fireEvent.changeText(screen.getByTestId('sales-create-tax-rate-0'), options.taxRate);
  }

  fireEvent.press(screen.getByTestId('sales-create-submit'));
}

describe('SalesOrderCreateScreen —— payload 真的带上了那些字段', () => {
  it('阳性对照：正常流程真的把单提交出去了（提交不了的话下面每条断言都无意义）', async () => {
    await fillAndSubmit({ customerId: 'c-taxed' });
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));
  });

  it('🔴 行项目带上 specification 与 boxQuantity（这两个字段以前一个都没发）', async () => {
    await fillAndSubmit({ customerId: 'c-taxed' });
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));

    const item = createOrder.mock.calls[0][0].items[0];

    // 阳性对照：一个一直都在的字段。它没了说明是我取错了对象，而不是缺陷复发。
    expect(item.productTypeId).toBe('p-1');

    expect(item.specification).toBe('310g*42袋/箱');
    // 24 盒 ÷ 12 = 2 箱。⛔ 不是 0、不是 undefined。
    expect(item.boxQuantity).toBe(2);
  });

  it('🔴 算不出箱数时送 undefined，⛔ 不送 0（0 会被仓库读成「零箱」）', async () => {
    getActiveProductTypes.mockResolvedValue([{ ...PRODUCT, boxConversionCoefficient: undefined }]);
    getPackagingSpecs.mockResolvedValue([]);

    await fillAndSubmit({ customerId: 'c-taxed' });
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));

    const item = createOrder.mock.calls[0][0].items[0];
    expect(item.boxQuantity).toBeUndefined();
    expect(item.boxQuantity).not.toBe(0);
  });

  it('规格取不到时送 undefined 而不是空串（后端才用得上 packagingSpecName 兜底）', async () => {
    getActiveProductTypes.mockResolvedValue([
      { ...PRODUCT, specification: undefined, packageSpec: undefined },
    ]);

    await fillAndSubmit({ customerId: 'c-taxed' });
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));

    expect(createOrder.mock.calls[0][0].items[0].specification).toBeUndefined();
  });

  it('业务员发的是数字 user id —— 后端靠它写 salesperson_id（佣金归属读这个 FK）', async () => {
    await fillAndSubmit({ customerId: 'c-taxed' });
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));

    // 非数字串会走 resolveSalespersonField 的老路径, 只写姓名不写 FK。
    expect(createOrder.mock.calls[0][0].salesperson).toBe('42');
    expect(/^\d+$/.test(createOrder.mock.calls[0][0].salesperson)).toBe(true);
  });
});

describe('SalesOrderCreateScreen —— 税率留空不许静默变成 0%', () => {
  it('🔴 客户没配默认税率 + 留空 → 拦住不提交，并明确告诉用户为什么', async () => {
    await fillAndSubmit({ customerId: 'c-plain' });

    await waitFor(() => expect(Alert.alert).toHaveBeenCalled());
    // 被守的行为: 这一单**没有**被提交出去。
    expect(createOrder).not.toHaveBeenCalled();

    const [title, body] = (Alert.alert as jest.Mock).mock.calls.at(-1)!;
    expect(title).toContain('税率');
    expect(body).toContain('0%');
  });

  it('客户配了默认税率 + 留空 → 放行，taxRate 不传（交给后端第 2 层）', async () => {
    await fillAndSubmit({ customerId: 'c-taxed' });
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));

    expect(createOrder.mock.calls[0][0].items[0].taxRate).toBeUndefined();
  });

  it('用户显式填了税率 → 原样发出去（含 0，免税品是合法的）', async () => {
    await fillAndSubmit({ customerId: 'c-plain', taxRate: '0' });
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));

    expect(createOrder.mock.calls[0][0].items[0].taxRate).toBe(0);
  });

  it('界面在提交前就说清留空会发生什么（防呆 Rule 1，不是提交后才报错）', async () => {
    render(<SalesOrderCreateScreen />);
    await waitFor(() => expect(screen.getByTestId('sales-create-customer-option-c-plain')).toBeTruthy());
    fireEvent.press(screen.getByTestId('sales-create-customer-option-c-plain'));

    await waitFor(() => {
      expect(screen.getByTestId('sales-create-tax-hint-0')).toBeTruthy();
    });
  });
});
