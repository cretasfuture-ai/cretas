import fs from 'fs';
import path from 'path';

const root = path.resolve(__dirname, '../../../');
const read = (rel: string) => fs.readFileSync(path.resolve(root, rel), 'utf8');
const strip = (source: string) => source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
const inbound = read('screens/warehouse/inbound/WHInboundListScreen.tsx');
const receiving = read('screens/warehouse/inbound/WHPurchaseReceiveListScreen.tsx');

describe('入库首页任务真值', () => {
  it('直接查询采购收货状态，不再读取物料批次', () => {
    expect(inbound).toContain('purchaseApiClient.getOrdersByStatus');
    for (const status of ['FINANCE_APPROVED', 'PARTIAL_RECEIVED', 'COMPLETED', 'CLOSED']) {
      expect(inbound).toContain(`'${status}'`);
    }
    expect(inbound).not.toMatch(/INBOUND_ORDER_STATUSES[\s\S]{0,220}'APPROVED'/);
    expect(receiving).not.toMatch(/RECEIVABLE_STATUSES[\s\S]{0,180}'APPROVED'/);
    expect(inbound).not.toContain('materialBatchApiClient');
    expect(inbound).not.toContain('getMaterialBatches');
  });

  it('全部列表按待收货、部分入库、已收货排序', () => {
    expect(inbound).toContain("pending: 0, partial: 1, completed: 2");
    expect(inbound).toContain('sortReceivingOrders');
    const sortLogic = read('screens/warehouse/warehouseList.logic.ts');
    expect(sortLogic).toContain('receivingBusinessTime(b).localeCompare(receivingBusinessTime(a))');
    expect(inbound).toContain("['completed', '已收货'");
    expect(inbound).toContain("completed: { label: '已收货'");
  });

  it('实际请求来源任一路失败就显式报错，不用残缺结果冒充完整计数', () => {
    expect(inbound).toContain('fulfilled.length !== requests.length');
    expect(inbound).toContain('入库任务加载失败');
  });

  it('搜索无匹配但仍有后续页时保留加载入口', () => {
    expect(inbound).toContain('visibleOrders.length === 0 && !hasMore');
    expect(inbound).toContain('testID="wh-inbound-load-more"');
  });

  it('部分成功保留列表，并只重试失败来源的原页码', () => {
    expect(inbound).toContain('failedStatusesRef.current[status] = request.page');
    expect(inbound).toContain("mode === 'retryFailed'");
    expect(inbound).toContain('retryFailed ? \'retryFailed\' : \'refresh\'');
    expect(inbound).toContain('loadError && orders.length === 0');
    expect(inbound).toContain('重试失败来源');
  });

  it('采购来源部分失败持续提示，并保留旧列表供仓管继续核对', () => {
    expect(receiving).toContain('setLoadError(info)');
    expect(receiving).toContain('部分来源失败时保留旧列表，同时补入本次成功来源');
    expect(receiving).not.toContain('setOrders([])');
    expect(receiving).toContain('请点击重试');
  });

  it('采购卡片不再显示财审通过或明细行', () => {
    expect(receiving).not.toContain("text: '财审通过'");
    expect(receiving).not.toContain('>明细行</Text>');
    expect(receiving).toContain("FINANCE_APPROVED:  { text: '待收货'");
  });

  it('不同单位不合计，逐项数量都带单位', () => {
    expect(receiving).toContain('uniformUnit');
    expect(receiving).toContain('多单位订单，请按下面明细逐项核对');
    expect(receiving).toContain('应收 {line.quantity || 0} {unit}');
    expect(receiving).toContain('已收 {line.receivedQuantity || 0} {unit}');
    expect(receiving).toContain('待收 {pending} {unit}');
  });

  it('状态筛选 Chip 触控区至少 44dp，横向间距至少 8dp', () => {
    expect(inbound).toContain('filters: { paddingHorizontal: 16, paddingVertical: 12, gap: 8 }');
    expect(inbound).toContain("filter: { minHeight: 44, marginRight: 0");
  });
});

describe('提前关单仍可用', () => {
  const receipt = strip(read('screens/warehouse/inbound/WHReceiptCreateScreen.tsx'));
  const client = read('services/api/warehouseReceivingApiClient.ts');

  it('短收关单有真实 API、安卓可用对话框和原因校验', () => {
    expect(client).toContain('/close-short');
    expect(receipt).toContain('isShortReceipt');
    expect(receipt).toContain('<Dialog');
    expect(receipt).toContain('RadioButton.Group');
    expect(receipt).not.toContain('Alert.prompt');
  });
});
