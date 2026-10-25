// 平台访问记录: 把接口路径翻译成工厂看得懂的功能名。
//
// 记录本身保留原始路径 (页面上作为小字附在功能名下面), 这里只负责「这是什么功能」。
// 页面自动发出的请求 (未读消息数、使用记录上报) 不是有人在看业务数据, 单独标成「后台自动」。

export interface AccessPathLabel {
  /** 功能名, 如「销售订单」。未识别时为「其他数据接口」。 */
  label: string;
  /** 页面自动发出的请求 (不含业务数据)。 */
  background: boolean;
}

interface Rule {
  pattern: RegExp;
  label: string;
  background?: boolean;
}

// 顺序有意义: 先匹配更具体的路径。路径已去掉 /api/mobile/{factoryId} 或 /api/platform 前缀。
const MOBILE_RULES: Rule[] = [
  { pattern: /^\/notifications\/unread-count/, label: '未读消息数（页面自动刷新）', background: true },
  { pattern: /^\/activity-events/, label: '使用记录上报（页面自动发送）', background: true },
  { pattern: /^\/notifications/, label: '消息通知' },
  { pattern: /^\/product-work-processes/, label: '产品工序' },
  { pattern: /^\/work-processes/, label: '工序' },
  { pattern: /^\/product-types/, label: '产品' },
  { pattern: /^\/raw-material-types/, label: '原料' },
  { pattern: /^\/material-batches/, label: '原料批次' },
  { pattern: /^\/conversions/, label: '原料转换率（用量）' },
  { pattern: /^\/process-secrets/, label: '工艺保密设置' },
  { pattern: /^\/factory\/warehouses/, label: '仓库' },
  { pattern: /^\/warehouse\/receiving/, label: '收货任务' },
  { pattern: /^\/warehouse/, label: '仓储' },
  { pattern: /^\/inventory/, label: '库存' },
  { pattern: /^\/sales\/orders/, label: '销售订单' },
  { pattern: /^\/sales/, label: '销售' },
  { pattern: /^\/purchase/, label: '采购' },
  { pattern: /^\/suppliers?/, label: '供应商' },
  { pattern: /^\/customers?/, label: '客户' },
  { pattern: /^\/operations\/customer-material-arrivals/, label: '客户来料到货' },
  { pattern: /^\/processing\/batches/, label: '生产批次' },
  { pattern: /^\/processing/, label: '生产加工' },
  { pattern: /^\/production/, label: '生产' },
  { pattern: /^\/quality/, label: '质检' },
  { pattern: /^\/equipment/, label: '设备' },
  { pattern: /^\/finance/, label: '财务' },
  { pattern: /^\/reports?/, label: '报表' },
  { pattern: /^\/dashboard/, label: '首页看板' },
  { pattern: /^\/users/, label: '员工账号' },
  { pattern: /^\/departments/, label: '部门' },
  { pattern: /^\/ai/, label: 'AI 助手' },
];

const PLATFORM_RULES: Rule[] = [
  { pattern: /\/ai-initialize\/converse/, label: '对话建厂·对话' },
  { pattern: /\/ai-initialize\/review/, label: '对话建厂·建厂审核' },
  { pattern: /\/ai-initialize\/blueprints/, label: '对话建厂·建厂方案' },
  { pattern: /\/ai-initialize\/interview-defaults/, label: '对话建厂·访谈默认值' },
  { pattern: /\/ai-initialize/, label: '对话建厂' },
  { pattern: /^\/factory-assembly/, label: '工厂配置' },
];

const UNKNOWN = '其他数据接口';

export function describeAccessPath(path: string | null | undefined): AccessPathLabel {
  if (!path) return { label: UNKNOWN, background: false };
  const mobile = /^\/api\/mobile\/[^/]+(\/.*)?$/.exec(path);
  if (mobile) {
    const rest = mobile[1] || '/';
    const hit = MOBILE_RULES.find((r) => r.pattern.test(rest));
    if (hit) return { label: hit.label, background: !!hit.background };
    return { label: UNKNOWN, background: false };
  }
  const platform = /^\/api\/platform(\/.*)$/.exec(path);
  if (platform) {
    const hit = PLATFORM_RULES.find((r) => r.pattern.test(platform[1]));
    if (hit) return { label: hit.label, background: false };
  }
  return { label: UNKNOWN, background: false };
}
