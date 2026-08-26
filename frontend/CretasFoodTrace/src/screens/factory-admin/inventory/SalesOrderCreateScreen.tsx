import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Platform, ScrollView, StyleSheet, TouchableOpacity, View } from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Dialog,
  IconButton,
  Menu,
  Portal,
  Text,
  TextInput,
} from 'react-native-paper';
import DateTimePicker from '@react-native-community/datetimepicker';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { FAManagementStackParamList } from '../../../types/navigation';
import { salesApiClient, CreateSalesOrderRequest, SalesOrder } from '../../../services/api/salesApiClient';
import { customerApiClient, Customer } from '../../../services/api/customerApiClient';
import { productTypeApiClient, ProductPackagingSpec, ProductType } from '../../../services/api/productTypeApiClient';
import { useAuthStore } from '../../../store/authStore';
import { formatDate, formatNumberWithCommas, parseYmdOrToday } from '../../../utils/formatters';
import SearchableDropdown from '../../../components/report/SearchableDropdown';
import {
  boxQuantityFor,
  decideTaxRate,
  packagingSpecsForUnit,
  salesUnitOptions,
  specificationSnapshot,
} from '../../../utils/salesOrderDraft';
import { todayIso } from '../../../utils/orderDate';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

interface DraftItem {
  key: string;
  productTypeId: string;
  productName: string;
  quantity: string;
  unitPrice: string;
  unit: string;
  taxRate: string;
  packagingSpecId: string;
  packagingSpecs: ProductPackagingSpec[];
  /** 选中 SKU 时快照下来的规格；随行提交给后端。 */
  specification: string;
  /** SKU 主数据的箱规系数，算箱数用（没有箱规行时的第二条路）。 */
  boxConversionCoefficient?: number;
  /** SKU 基本单位，判「是不是按基本单位下的单」用。 */
  productBaseUnit: string;
}

// todayIso 收敛到 utils/orderDate —— 原来这里是私有副本, 且用 toISOString() 按 UTC 截断,
// 东八区当天 08:00 前会把下单日期记成前一天。

const tomorrowIso = () => {
  const next = new Date();
  next.setDate(next.getDate() + 1);
  // 2026-08-26: 上面那条注释说的就是这个坑, 而这一行自己还在踩 ——
  // toISOString() 按 UTC 截断, 东八区 08:00 前会把「明天」算成「今天」。
  return formatDate(next);
};

// 要求交付日期在这条链上的唯一格式是 YYYY-MM-DD
// (提交前有 /^\d{4}-\d{2}-\d{2}$/ 校验, 拼错整单提交不了)。
// 滚轮选完之后必须经过这里再写回, 不要在别处另拼一份。
const blankItem = (): DraftItem => ({
  key: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  productTypeId: '',
  productName: '',
  quantity: '',
  unitPrice: '',
  unit: '',
  taxRate: '',
  packagingSpecId: '',
  packagingSpecs: [],
  specification: '',
  boxConversionCoefficient: undefined,
  productBaseUnit: '',
});

export default function SalesOrderCreateScreen() {
  const navigation = useNavigation<Nav>();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [customers, setCustomers] = useState<Customer[]>([]);
  const [products, setProducts] = useState<ProductType[]>([]);
  const [customerId, setCustomerId] = useState('');
  const [customerName, setCustomerName] = useState('');
  const [requiredDeliveryDate, setRequiredDeliveryDate] = useState(tomorrowIso());
  const [remark, setRemark] = useState('');
  const [items, setItems] = useState<DraftItem[]>([blankItem()]);
  const [openMenuFor, setOpenMenuFor] = useState<{ kind: 'customer' | 'product' | 'packaging'; key?: string } | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [createdOrder, setCreatedOrder] = useState<SalesOrder | null>(null);

  // ============================================================
  // 要求交付日期滚轮 (2026-08-26)
  // 现场原话:「这个日期改下备, 改成滚轮的, 手写有点费事」
  // ============================================================
  const [datePickerVisible, setDatePickerVisible] = useState(false);
  const pickerInitialDate = useMemo(() => parseYmdOrToday(requiredDeliveryDate), [requiredDeliveryDate]);
  // ⛔ 写回一律走 formatYmd —— validate() 里那道 /^\d{4}-\d{2}-\d{2}$/ 拦的就是这个格式。
  const commitPickedDate = useCallback((date: Date) => {
    setRequiredDeliveryDate(formatDate(date));
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const [customerList, productList] = await Promise.all([
          customerApiClient.getActiveCustomers(factoryId),
          productTypeApiClient.getActiveProductTypes(factoryId),
        ]);
        setCustomers(customerList || []);
        setProducts(productList || []);
      } catch (error) {
        const message = error instanceof Error ? error.message : '页面数据加载失败';
        Alert.alert('错误', message);
      } finally {
        setLoading(false);
      }
    })();
  }, [factoryId]);

  const totalAmount = useMemo(() => {
    return items.reduce((sum, item) => {
      const quantity = Number(item.quantity);
      const price = Number(item.unitPrice);
      if (!Number.isFinite(quantity) || !Number.isFinite(price)) return sum;
      return sum + quantity * price;
    }, 0);
  }, [items]);

  const updateItem = (key: string, patch: Partial<DraftItem>) => {
    setItems((prev) => prev.map((item) => (item.key === key ? { ...item, ...patch } : item)));
  };

  const addItem = () => {
    setItems((prev) => [...prev, blankItem()]);
  };

  const removeItem = (key: string) => {
    setItems((prev) => (prev.length === 1 ? prev : prev.filter((item) => item.key !== key)));
  };

  const selectedCustomer = useMemo(
    () => customers.find((entry) => entry.id === customerId),
    [customers, customerId],
  );

  const packagingOptions = (item: DraftItem) => packagingSpecsForUnit(item.unit, item.packagingSpecs);

  /** 本行选中的箱规（用户没选、或该单位下只有一条时的那条）。 */
  const selectedPackagingSpec = (item: DraftItem) =>
    item.packagingSpecs.find((spec) => spec.id === item.packagingSpecId);

  /** 本行会随 payload 发出去的箱数。undefined = 算不出来，界面照实说，⛔ 不落成 0。 */
  const boxQuantityOf = (item: DraftItem) =>
    boxQuantityFor({
      quantity: item.quantity,
      unit: item.unit,
      specification: item.specification,
      packagingSpec: selectedPackagingSpec(item),
      productBaseUnit: item.productBaseUnit,
      productBoxConversionCoefficient: item.boxConversionCoefficient,
    });

  const taxDecisionOf = (item: DraftItem) =>
    decideTaxRate(item.taxRate, selectedCustomer?.defaultTaxRate);

  const handleUnitChange = (item: DraftItem, unit: string) => {
    const options = item.packagingSpecs.filter((spec) => spec.active !== false && spec.packageUnit === unit);
    updateItem(item.key, {
      unit,
      packagingSpecId: options.length === 1 ? options[0]!.id : '',
    });
  };

  const selectProduct = async (item: DraftItem, product: ProductType) => {
    updateItem(item.key, {
      productTypeId: product.id,
      productName: product.name,
      unit: product.unit || 'kg',
      unitPrice: product.unitPrice != null ? String(product.unitPrice) : item.unitPrice,
      packagingSpecId: '',
      packagingSpecs: [],
      // 规格随 SKU 快照下来 —— 与 web-admin onProductSelect 同口径。
      specification: specificationSnapshot(product.specification, product.packageSpec) ?? '',
      boxConversionCoefficient: product.boxConversionCoefficient,
      productBaseUnit: product.unit || 'kg',
    });
    setOpenMenuFor(null);
    try {
      const specs = await productTypeApiClient.getPackagingSpecs(product.id, factoryId);
      updateItem(item.key, { packagingSpecs: specs });
    } catch (error) {
      const message = error instanceof Error ? error.message : '包装规格加载失败';
      Alert.alert('错误', message);
    }
  };

  const validate = (): DraftItem[] | null => {
    if (!customerId) {
      Alert.alert('提示', '请选择客户');
      return null;
    }
    if (requiredDeliveryDate && !/^\d{4}-\d{2}-\d{2}$/.test(requiredDeliveryDate)) {
      Alert.alert('提示', '要求交付日期格式应为 YYYY-MM-DD');
      return null;
    }

    const cleanedItems = items.filter((item) => item.productTypeId || item.quantity || item.unitPrice);
    if (cleanedItems.length === 0) {
      Alert.alert('提示', '至少填写一行产品明细');
      return null;
    }

    const invalidLine = cleanedItems.find((item) => {
      const quantity = Number(item.quantity);
      const unitPrice = Number(item.unitPrice);
      const taxRateInvalid = taxDecisionOf(item).kind === 'invalid';
      return !item.productTypeId || !item.unit || !Number.isFinite(quantity) || quantity <= 0
        || !Number.isFinite(unitPrice) || unitPrice < 0 || taxRateInvalid;
    });

    if (invalidLine) {
      Alert.alert('提示', '产品、数量、单位、单价必须填写完整；数量需大于 0，单价不能小于 0，税率应在 0–100 之间');
      return null;
    }

    // 客户没配默认税率时，留空**不是** 0% —— 后端会静默兜底成 0 把税算没
    // (SalesServiceImpl:685-688)。2026-08-19 prod 实测 29 个客户里 22 个没配。
    // ⇒ 这种时候必须让用户显式填，而不是替他决定一个数（防呆 Rule 1：提交前看到边界）。
    const missingTaxRate = cleanedItems.find((item) => taxDecisionOf(item).kind === 'must-ask');
    if (missingTaxRate) {
      Alert.alert(
        '请填写税率',
        `客户「${customerName || '（未命名）'}」没有配置默认税率，留空会按 0% 计税。\n\n`
          + `请为「${missingTaxRate.productName || '该产品'}」填写本单税率，`
          + '或先在客户资料里维护默认税率。',
      );
      return null;
    }

    const missingPackaging = cleanedItems.find(
      (item) => packagingOptions(item).length > 1 && !item.packagingSpecId
    );
    if (missingPackaging) {
      Alert.alert('提示', `${missingPackaging.productName || '产品'}有多个装箱规格，请选择本次使用的箱规`);
      return null;
    }

    return cleanedItems;
  };

  const handleSubmit = async () => {
    if (createdOrder) {
      navigation.goBack();
      return;
    }

    const cleanedItems = validate();
    if (!cleanedItems) return;

    const payload: CreateSalesOrderRequest = {
      customerId,
      // This compact RN screen has no structured customer-material editor. Keep its
      // contract explicit and fail-safe instead of relying on a backend default.
      processingMode: 'STANDARD_SALE',
      materialSupplyMode: 'FACTORY_SUPPLIED',
      orderDate: todayIso(),
      requiredDeliveryDate: requiredDeliveryDate || undefined,
      // Backend resolves a numeric user id into both the salesperson FK and the name snapshot.
      salesperson: user?.id != null ? String(user.id) : undefined,
      remark: remark || undefined,
      items: cleanedItems.map((item) => {
        const taxDecision = taxDecisionOf(item);
        return {
          productTypeId: item.productTypeId,
          productName: item.productName,
          quantity: Number(item.quantity),
          unitPrice: Number(item.unitPrice),
          unit: item.unit,
          // 只有两种可能到这里: explicit(用户填了) / customer-default(客户配了默认, 交给后端第 2 层).
          // must-ask 与 invalid 已在 validate() 拦掉 —— 留空绝不会静默落成 0%。
          taxRate: taxDecision.kind === 'explicit' ? taxDecision.taxRate : undefined,
          packagingSpecId: item.packagingSpecId || undefined,
          // 后端 applyLineDisplaySnapshots 只在这里 blank 时才退到 packagingSpecName,
          // 所以空值送 undefined 而不是 ''。
          specification: item.specification.trim() || undefined,
          // 算不出来就不发这个键 —— 后端不做推导, 但一个假的 0 会被仓库读成「零箱」。
          boxQuantity: boxQuantityOf(item),
        };
      }),
    };

    try {
      setSubmitting(true);
      const response = await salesApiClient.createOrder(payload, factoryId);
      setCreatedOrder(response.data);
    } catch (error) {
      const message = error instanceof Error ? error.message : '创建销售订单失败';
      Alert.alert('错误', message);
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']} testID="sales-order-create-screen">
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="新建销售订单" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']} testID="sales-order-create-screen">
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="新建销售订单" />
        <Appbar.Action icon="check" testID="sales-create-submit-icon" disabled={submitting || !!createdOrder} onPress={handleSubmit} />
      </Appbar.Header>

      <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
        {createdOrder && (
          <Card style={styles.successCard} testID="sales-create-success-card">
            <Card.Content>
              <Text style={styles.successTitle}>订单已创建</Text>
              <Text style={styles.successText}>{createdOrder.orderNumber || createdOrder.id}</Text>
              <Text style={styles.successHint}>状态为草稿，确认后进入后续财务/生产流程。</Text>
            </Card.Content>
          </Card>
        )}

        <Card style={styles.card}>
          <Card.Content>
            <Text style={styles.sectionTitle}>订单信息</Text>
            <SearchableDropdown
              label="客户"
              required
              placeholder="搜索并选择客户"
              options={customers.map((customer) => ({
                value: customer.id,
                label: `${customer.name}${customer.customerCode ? ` (${customer.customerCode})` : ''}`,
              }))}
              value={customerId}
              onSelect={(value) => {
                const customer = customers.find((entry) => entry.id === value);
                setCustomerId(value);
                setCustomerName(customer?.name || '');
              }}
              allowCustom={false}
              testID="sales-create-customer"
            />

            {/* 2026-08-26 改成滚轮选, 不再手打
                (editable={false} + 外层 TouchableOpacity + 内层 pointerEvents="none") */}
            <View style={styles.fieldBlock}>
              <Text style={styles.fieldLabel}>要求交付日期</Text>
              <TouchableOpacity
                onPress={() => setDatePickerVisible(true)}
                accessibilityRole="button"
                accessibilityLabel="选择要求交付日期"
                testID="sales-create-required-date-picker"
              >
                <View pointerEvents="none">
                  <TextInput
                    testID="sales-create-required-date"
                    value={requiredDeliveryDate}
                    mode="outlined"
                    editable={false}
                    right={<TextInput.Icon icon="calendar" />}
                    style={styles.input}
                  />
                </View>
              </TouchableOpacity>
            </View>

            <View style={styles.fieldBlock}>
              <Text style={styles.fieldLabel}>备注</Text>
              <TextInput
                testID="sales-create-remark"
                value={remark}
                onChangeText={setRemark}
                mode="outlined"
                multiline
                numberOfLines={2}
                style={styles.input}
              />
            </View>
          </Card.Content>
        </Card>

        <Card style={styles.card}>
          <Card.Content>
            <View style={styles.sectionHeader}>
              <Text style={styles.sectionTitle}>产品明细</Text>
              <Button compact mode="outlined" onPress={addItem} testID="sales-create-add-item">加一行</Button>
            </View>

            {items.map((item, index) => (
              <View key={item.key} style={styles.itemBlock} testID={`sales-create-item-${index}`}>
                <View style={styles.itemHeader}>
                  <Text style={styles.itemTitle}>第 {index + 1} 行</Text>
                  {items.length > 1 && (
                    <IconButton
                      icon="delete-outline"
                      size={20}
                      onPress={() => removeItem(item.key)}
                      testID={`sales-create-remove-item-${index}`}
                    />
                  )}
                </View>

                <SearchableDropdown
                  label="产品"
                  required
                  placeholder="按名称或 SKU 搜索"
                  options={products.map((product) => ({
                    value: product.id,
                    label: `${product.name}${product.productCode ? ` (${product.productCode})` : ''}`,
                    description: [product.category, product.unit].filter(Boolean).join(' · ') || undefined,
                  }))}
                  value={item.productTypeId}
                  onSelect={(value) => {
                    const product = products.find((entry) => entry.id === value);
                    if (product) void selectProduct(item, product);
                  }}
                  allowCustom={false}
                  testID={`sales-create-product-${index}`}
                />

                <View style={styles.threeColumns}>
                  <View style={styles.thirdField}>
                    <Text style={styles.fieldLabel}>数量</Text>
                    <TextInput
                      testID={`sales-create-quantity-${index}`}
                      value={item.quantity}
                      onChangeText={(value) => updateItem(item.key, { quantity: value })}
                      mode="outlined"
                      keyboardType="decimal-pad"
                      style={styles.input}
                    />
                  </View>
                  <View style={styles.thirdField}>
                    <SearchableDropdown
                      label="单位"
                      required
                      placeholder={item.productTypeId ? '请选择单位' : '先选产品'}
                      options={salesUnitOptions(
                        products.find((product) => product.id === item.productTypeId)?.unit || '',
                        item.packagingSpecs,
                      ).map((unit) => ({ value: unit, label: unit }))}
                      value={item.unit}
                      onSelect={(value) => handleUnitChange(item, value)}
                      allowCustom={false}
                      testID={`sales-create-unit-${index}`}
                    />
                  </View>
                  <View style={styles.thirdField}>
                    <Text style={styles.fieldLabel}>单价</Text>
                    <TextInput
                      testID={`sales-create-unit-price-${index}`}
                      value={item.unitPrice}
                      onChangeText={(value) => updateItem(item.key, { unitPrice: value })}
                      mode="outlined"
                      keyboardType="decimal-pad"
                      style={styles.input}
                    />
                  </View>
                </View>
                <View style={styles.fieldBlock}>
                  <Text style={styles.fieldLabel}>税率 (%)</Text>
                  <TextInput
                    testID={`sales-create-tax-rate-${index}`}
                    value={item.taxRate}
                    onChangeText={(value) => updateItem(item.key, { taxRate: value })}
                    mode="outlined"
                    keyboardType="decimal-pad"
                    placeholder={
                      taxDecisionOf(item).kind === 'customer-default'
                        ? `留空按客户默认 ${selectedCustomer?.defaultTaxRate}%`
                        : '请填写本单税率'
                    }
                    style={styles.input}
                  />
                  {/* 防呆 Rule 1: 提交前就把「留空会发生什么」说清楚, 不等提交后再报错。 */}
                  {(() => {
                    const decision = taxDecisionOf(item);
                    if (decision.kind === 'customer-default') {
                      return (
                        <Text style={styles.hintText} testID={`sales-create-tax-hint-${index}`}>
                          留空将使用客户「{customerName}」的默认税率 {decision.customerDefaultTaxRate}%
                        </Text>
                      );
                    }
                    if (decision.kind === 'must-ask') {
                      return (
                        <Text style={styles.warnText} testID={`sales-create-tax-hint-${index}`}>
                          {customerId
                            ? `客户「${customerName}」未配置默认税率 —— 留空会按 0% 计税，请填写`
                            : '请先选择客户，才能判断留空时用哪个默认税率'}
                        </Text>
                      );
                    }
                    return null;
                  })()}
                </View>

                {/* 规格 / 箱数都是只读快照 —— 随单发给后端, 仓库拣货照着看。
                    算不出箱数就照实说「未配置箱规」, ⛔ 不显示也不发送 0。 */}
                {!!item.productTypeId && (
                  <View style={styles.snapshotRow} testID={`sales-create-snapshot-${index}`}>
                    <Text style={styles.snapshotText} testID={`sales-create-specification-${index}`}>
                      规格：{item.specification || '产品字典未维护规格'}
                    </Text>
                    <Text style={styles.snapshotText} testID={`sales-create-box-quantity-${index}`}>
                      箱数：{(() => {
                        const box = boxQuantityOf(item);
                        if (box !== undefined) return String(box);
                        return item.specification.trim() === '抄码'
                          ? '抄码品不计箱数'
                          : '未配置箱规，请在产品字典维护';
                      })()}
                    </Text>
                  </View>
                )}
                {packagingOptions(item).length > 0 && (
                  <Menu
                    visible={openMenuFor?.kind === 'packaging' && openMenuFor.key === item.key}
                    onDismiss={() => setOpenMenuFor(null)}
                    anchor={
                      <View style={styles.selectorBlock}>
                        <Text style={styles.fieldLabel}>包装规格</Text>
                        <Button
                          mode="outlined"
                          icon="chevron-down"
                          contentStyle={styles.selectorContent}
                          labelStyle={styles.selectorLabel}
                          onPress={() => setOpenMenuFor({ kind: 'packaging', key: item.key })}
                        >
                          {packagingOptions(item).find((spec) => spec.id === item.packagingSpecId)
                            ? (() => {
                                const spec = packagingOptions(item).find((entry) => entry.id === item.packagingSpecId)!;
                                return `1${spec.packageUnit}=${spec.conversionFactor}${spec.baseUnit}`;
                              })()
                            : packagingOptions(item).length > 1 ? '请选择本次箱规' : '默认箱规'}
                        </Button>
                      </View>
                    }
                  >
                    {packagingOptions(item).map((spec) => (
                      <Menu.Item
                        key={spec.id}
                        title={`1${spec.packageUnit}=${spec.conversionFactor}${spec.baseUnit}`}
                        onPress={() => {
                          updateItem(item.key, { packagingSpecId: spec.id });
                          setOpenMenuFor(null);
                        }}
                      />
                    ))}
                  </Menu>
                )}
              </View>
            ))}
          </Card.Content>
        </Card>

        <Card style={styles.summaryCard}>
          <Card.Content>
            <View style={styles.summaryRow}>
              <Text style={styles.summaryLabel}>订单合计</Text>
              <Text style={styles.summaryAmount}>¥{formatNumberWithCommas(totalAmount)}</Text>
            </View>
            <Text style={styles.guardText}>低毛利、超账期、授信异常由财务审批规则复核；销售端先保证客户、数量、价格清楚。</Text>
          </Card.Content>
        </Card>
      </ScrollView>

      <View style={styles.footer}>
        <Button
          mode="contained"
          icon="check"
          loading={submitting}
          disabled={submitting}
          onPress={handleSubmit}
          testID="sales-create-submit"
          style={styles.submitButton}
        >
          {createdOrder ? '返回订单列表' : '创建订单'}
        </Button>
      </View>

      {/* 要求交付日期滚轮 —— 整屏只有这一个实例。
          用户要的是滚轮, 所以两端都显式 display="spinner"(Android 默认是日历不是滚轮)。 */}
      {datePickerVisible && (Platform.OS === 'ios' ? (
        <Portal>
          <Dialog visible onDismiss={() => setDatePickerVisible(false)}>
            <Dialog.Title>选择要求交付日期</Dialog.Title>
            <Dialog.Content>
              <DateTimePicker
                value={pickerInitialDate}
                mode="date"
                display="spinner"
                onChange={(_event, date) => {
                  if (date) commitPickedDate(date);
                }}
              />
            </Dialog.Content>
            <Dialog.Actions>
              <Button onPress={() => setDatePickerVisible(false)}>完成</Button>
            </Dialog.Actions>
          </Dialog>
        </Portal>
      ) : (
        <DateTimePicker
          value={pickerInitialDate}
          mode="date"
          display="spinner"
          onChange={(event, date) => {
            // Android 是模态: 无论确定还是取消都要收起, 否则下次点不开
            setDatePickerVisible(false);
            if (event.type === 'set' && date) commitPickedDate(date);
          }}
        />
      ))}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  scroll: { flex: 1 },
  scrollContent: { padding: 12, paddingBottom: 96 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  loadingText: { marginTop: 10, color: '#666' },
  card: { marginBottom: 12, borderRadius: 8 },
  successCard: { marginBottom: 12, borderRadius: 8, backgroundColor: '#edf8f1' },
  successTitle: { color: '#16794c', fontSize: 17, fontWeight: '800', marginBottom: 6 },
  successText: { color: '#1f2933', fontSize: 15, fontWeight: '700' },
  successHint: { color: '#4d6558', marginTop: 6, lineHeight: 20 },
  summaryCard: { marginBottom: 12, borderRadius: 8, backgroundColor: '#fffaf4' },
  sectionHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: '#1f2933', marginBottom: 8 },
  input: { marginBottom: 10, backgroundColor: '#fff' },
  fieldBlock: { marginBottom: 2 },
  selectorBlock: { marginBottom: 10 },
  fieldLabel: { marginBottom: 6, color: '#59636e', fontSize: 13 },
  hintText: { marginTop: -4, marginBottom: 10, color: '#59636e', fontSize: 12, lineHeight: 18 },
  warnText: { marginTop: -4, marginBottom: 10, color: '#b3541e', fontSize: 12, lineHeight: 18 },
  snapshotRow: { marginBottom: 10, paddingVertical: 6, paddingHorizontal: 8, backgroundColor: '#f3f6f9', borderRadius: 6 },
  snapshotText: { color: '#3d4852', fontSize: 12, lineHeight: 20 },
  selectorContent: { minHeight: 48, justifyContent: 'flex-start' },
  selectorLabel: { flex: 1, textAlign: 'left' },
  itemBlock: { paddingTop: 6, marginTop: 8, borderTopWidth: 1, borderTopColor: '#edf0f2' },
  itemHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  itemTitle: { fontWeight: '600', color: '#3d4852' },
  threeColumns: { flexDirection: 'row', gap: 8 },
  thirdField: { flex: 1 },
  summaryRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  summaryLabel: { color: '#59636e', fontSize: 14 },
  summaryAmount: { color: '#d35400', fontSize: 20, fontWeight: '800' },
  guardText: { marginTop: 8, color: '#6b5b3e', lineHeight: 20 },
  footer: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    padding: 12,
    backgroundColor: '#ffffff',
    borderTopWidth: 1,
    borderTopColor: '#e6e8eb',
  },
  submitButton: { borderRadius: 6 },
});
