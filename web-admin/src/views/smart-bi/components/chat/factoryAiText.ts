/**
 * 工厂侧 AI 回答的展示前清洗 (对话标准 V3: 不显示英文字段名 / 英文状态码)。
 *
 * 问题 52 实测: 「还需要以下信息：indicator_code、indicator_codes」原样露给客户。
 * 已知参数名换成中文; 未知的 snake_case 英文标识整段去掉, 再收拾剩下的标点。
 * 不动中文、数字、金额、日期; 不动普通英文单词 (如 SKU、Top)。
 */
const FIELD_LABELS: Record<string, string> = {
  indicator_code: '指标',
  indicator_codes: '指标',
  metric_code: '指标',
  start_date: '开始日期',
  end_date: '结束日期',
  date_range: '时间范围',
  time_range: '时间范围',
  product_id: '产品',
  product_name: '产品',
  customer_id: '客户',
  customer_name: '客户',
  supplier_id: '供应商',
  material_id: '原料',
  batch_id: '批次',
  batch_no: '批次号',
  factory_id: '工厂',
  upload_id: '数据源',
  store_id: '门店',
  dimension: '维度',
  quantity: '数量',
};

const SNAKE_IDENTIFIER = /\b[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+\b/g;
const UPPER_CODE = /\b[A-Z][A-Z_]{3,}\b/g;

export function sanitizeFactoryAiText(input: string): string {
  if (!input) return '';
  let text = input.replace(SNAKE_IDENTIFIER, (m) => FIELD_LABELS[m.toLowerCase()] ?? '');
  text = text.replace(UPPER_CODE, '');
  // 同一个中文标签连写 (indicator_code、indicator_codes → 指标、指标) 只留一个
  text = text.replace(/([一-鿿]{1,8})(?:[、，,]\s*\1)+(?![一-鿿])/g, '$1');
  text = text
    .replace(/[（(]\s*[)）]/g, '')
    .replace(/([：:])\s*[、，,；;]+/g, '$1')
    .replace(/[、，,]\s*(?=[、，,。；;\n]|$)/g, '')
    .replace(/[：:]\s*(?=[。；;\n]|$)/g, '')
    .replace(/[ \t]{2,}/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return text;
}
