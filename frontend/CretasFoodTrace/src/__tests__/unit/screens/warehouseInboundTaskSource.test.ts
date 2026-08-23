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
    for (const status of ['APPROVED', 'FINANCE_APPROVED', 'PARTIAL_RECEIVED', 'COMPLETED']) {
      expect(inbound).toContain(`'${status}'`);
    }
    expect(inbound).not.toContain('materialBatchApiClient');
    expect(inbound).not.toContain('getMaterialBatches');
  });

  it('全部列表按待收货、部分入库、已完成排序', () => {
    expect(inbound).toContain("pending: 0, partial: 1, completed: 2");
    expect(inbound).toContain('sortReceivingOrders');
  });

  it('四类状态任一路失败就显式报错，不用残缺结果冒充完整计数', () => {
    expect(inbound).toContain('fulfilled.length !== INBOUND_ORDER_STATUSES.length');
    expect(inbound).toContain('入库任务加载失败');
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
