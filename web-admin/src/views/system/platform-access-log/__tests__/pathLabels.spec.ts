import { describe, expect, it } from 'vitest';
import { describeAccessPath } from '../pathLabels';

// 2026-10-25 线上 platform_data_access_log 里实际出现过的全部路径 (按 http_method + 路径去重, 共 29 类)。
const PROD_PATHS: Array<[string, string]> = [
  ['/api/mobile/F006/notifications/unread-count', '未读消息数（页面自动刷新）'],
  ['/api/mobile/F006/product-work-processes', '产品工序'],
  ['/api/mobile/F006/product-types', '产品'],
  ['/api/mobile/F006/raw-material-types', '原料'],
  ['/api/mobile/F006/work-processes', '工序'],
  ['/api/mobile/F006/conversions', '原料转换率（用量）'],
  ['/api/mobile/F006/factory/warehouses', '仓库'],
  ['/api/mobile/F006/activity-events', '使用记录上报（页面自动发送）'],
  ['/api/mobile/F006/sales/orders', '销售订单'],
  ['/api/mobile/F006/warehouse/receiving/tasks', '收货任务'],
  ['/api/mobile/F006/operations/customer-material-arrivals', '客户来料到货'],
  ['/api/mobile/F006/processing/batches-with-clerk-wip', '生产批次'],
  ['/api/platform/factory-assembly/factories/F006/ai-initialize/blueprints/current', '对话建厂·建厂方案'],
  ['/api/platform/factory-assembly/factories/F006/ai-initialize/blueprints/catalog', '对话建厂·建厂方案'],
  ['/api/platform/factory-assembly/factories/DEMO_2026_001/ai-initialize/blueprints/7ee2e2e3/readiness', '对话建厂·建厂方案'],
  ['/api/platform/factory-assembly/factories/DEMO_2026_001/ai-initialize/review', '对话建厂·建厂审核'],
  ['/api/platform/factory-assembly/factories/F006/ai-initialize/converse', '对话建厂·对话'],
  ['/api/platform/factory-assembly/factories/F006/ai-initialize/converse/recoverable', '对话建厂·对话'],
  ['/api/platform/factory-assembly/factories/F006/ai-initialize/converse/46f71338/reply', '对话建厂·对话'],
  ['/api/platform/factory-assembly/factories/F006/ai-initialize/interview-defaults', '对话建厂·访谈默认值'],
];

describe('describeAccessPath', () => {
  it.each(PROD_PATHS)('%s -> %s', (path, label) => {
    expect(describeAccessPath(path).label).toBe(label);
  });

  it('marks only the page-generated requests as background', () => {
    const background = PROD_PATHS.filter(([p]) => describeAccessPath(p).background).map(([p]) => p);
    expect(background).toEqual([
      '/api/mobile/F006/notifications/unread-count',
      '/api/mobile/F006/activity-events',
    ]);
    // 其它消息接口是有人在看消息, 不算后台
    expect(describeAccessPath('/api/mobile/F006/notifications').background).toBe(false);
  });

  it('falls back to a neutral label instead of guessing', () => {
    expect(describeAccessPath('/api/mobile/F006/something-new')).toEqual({ label: '其他数据接口', background: false });
    expect(describeAccessPath('/api/other/x').label).toBe('其他数据接口');
    expect(describeAccessPath(null).label).toBe('其他数据接口');
  });
});
