const FIELD_LABELS: Record<string, string> = {
  action: '建议动作',
  actual: '实际产量',
  baselineProject: '最低成本工单',
  comparisonProject: '最高成本工单',
  comparabilityVerified: '产品规格与工艺是否已人工核实一致',
  eligibleProjects: '资料具备的工单数',
  currency: '币种',
  delta: '差额',
  finishedGood: '成品良品量',
  high: '较高值',
  hours: '累计工时',
  issues: '待核对事项',
  knownMaterialCostLines: '有来源金额的材料行数',
  knownTasks: '有记录的工序任务数',
  laborCostKnownTasks: '有工资工序数',
  laborCost: '人工累计',
  laborShare: '人工占比',
  linkedLines: '已关联领退料行数',
  lines: '领退料明细',
  low: '较低值',
  material: '原料',
  materials: '材料明细',
  materialShare: '材料占比',
  method: '判断依据',
  materialCode: '物料编码',
  materialCost: '材料金额',
  materialLinesDetail: '材料明细',
  materialLines: '领退料行数',
  materialCostKnownLines: '有来源金额的材料行数',
  'materialLinesDetail.recognizedCost': '材料明细中的来源金额',
  'materialLinesDetail.unitPrice': '材料明细中的来源单价',
  maximum: '最高值',
  minimum: '最低值',
  minimumForSpreadReview: '进行差异比较至少需要的工单数',
  names: '来源产品名称',
  netConsumption: '净消耗',
  negativeControlVerified: '负向对照已核实',
  orderCode: '领退料单',
  processes: '工序明细',
  product: '产品',
  productCode: '产品编码',
  projectCode: '工单编码',
  recognizedCost: '来源金额',
  sourceUnitQty: '来源单位用量',
  sourceZeroCostLines: '来源金额为零的材料行数',
  sampleSize: '参与比较的工单数',
  spread: '最高比最低高出',
  status: '当前状态',
  taskCount: '工序任务数',
  threshold: '提醒标准',
  unit: '单位',
  unitCostAvailable: '单位直接成本可展示',
  unitIdentifiedDirectCost: '单位直接成本',
  unitPrice: '来源单价',
  unresolvedLinks: '未确认关联',
  usage: '耗用数量',
};

const TERM_LABELS: Record<string, string> = {
  business_signal: '成本差异信号',
  data_quality: '数据质量',
  estimate: '计价',
  finishedGood: '成品良品量',
  laborCost: '人工累计',
  non_positive: '没有提供或小于等于零',
  nonPositive: '没有提供或小于等于零',
  aggregation: '记入对应工单',
  estimate_value: '计价依据',
  nonpositive: '没有提供或小于等于零',
  '估值': '计价',
  '归集': '记入对应工单',
  '三元组': '工单关联信息',
  '非正数': '没有提供或小于等于零',
  missing_cost: '缺少成本金额',
  missing_labor: '缺少人工累计',
  missing_output: '缺少成品数量',
  readiness: '核算准备条件',
  recognizedCost: '来源金额',
  source_zero_cost: '来源金额为零',
  unlinked_material: '领退料未关联工单',
};

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function localizeBlacklakeField(value: unknown): string {
  const key = String(value ?? '').trim();
  const lastSegment = key.split('.').pop() || '';
  return FIELD_LABELS[key] || FIELD_LABELS[lastSegment] || TERM_LABELS[key] || (key ? '来源字段' : '未标注字段');
}

export function localizeBlacklakeText(value: unknown, fallback = '来源系统未提供可读说明'): string {
  const text = String(value ?? '').trim();
  if (!text) return fallback;
  if (/^\d+$/.test(text) || isOpaqueIdentifier(text)) return fallback;
  if (/^[A-Za-z][A-Za-z0-9_.:-]*$/.test(text) && !/[\u4e00-\u9fff]/.test(text)) {
    return TERM_LABELS[text] || fallback;
  }
  const translatedPhrases = [
    ['关联仅为候选，非实际流转证明', '目前只能对上业务单据，还不能确认用了哪个实际批次'],
    ['上游单位与成品不同', '领入材料与成品使用不同单位'],
    ['批次未核', '实际用料批次还没核实'],
    ['成本归集', '成本记录'],
    ['归集口径', '记账范围'],
    ['确认是否漏估值或确为零成本', '确认材料当时是否没有记价，或确实为零成本'],
    ['核对领退料与工单关联三元组', '核对领退料的工单编码、业务单类型和关联编号'],
    ['同代码同单位工单', '同产品、同单位的工单'],
    ['复核阈值', '提醒标准'],
    ['仅为候选信号，非已证实经营异常', '需要回看工单核实，暂不能确定生产真的有问题'],
    ['确认是否漏估值', '确认材料当时是否没有记价，或确实为零成本'],
    ['当时估值依据', '当时的计价依据'],
    ['最终良品量缺失或非正数', '成品良品量没有提供，或小于等于零'],
    ['领退料与工单关联三元组', '核对领退料的工单编码、业务单类型和关联编号'],
    ['核对归集口径', '核对材料和工资有没有记全、记对'],
    ['材料归集完整', '材料是否记全'],
    ['归集完整性', '记录是否完整'],
  ].sort((a,b)=>b[0].length-a[0].length).reduce((result, [phrase, replacement]) => result.split(phrase).join(replacement), text);
  const translated = Object.entries(TERM_LABELS).reduce((result, [term, label]) => {
    if (/[\u4e00-\u9fff]/.test(term)) return result.split(term).join(label);
    return result.replace(new RegExp(`\\b${escapeRegExp(term)}\\b`, 'gi'), label);
  }, translatedPhrases);
  return translated.replace(/(^|[^\p{L}\p{N}_.])(-?\d+\.\d+)(?!-\d)(?=$|[^\p{L}\p{N}_.]|来源金额|KG\b|kg\b|元|包|盒|公斤|小时)/gu, (_match, prefix: string, number: string) => `${prefix}${formatBlacklakeNumber(number)}`);
}

export function formatBlacklakeNumber(value: unknown, fallback = '未提供'): string {
  if (value === null || value === undefined || value === '') return fallback;
  const number = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(number)) return fallback;
  return number.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function isOpaqueIdentifier(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f-]{17,}$/i.test(value) || /^\d{8,}$/.test(value);
}

const STATUS_LABELS: Record<string, string> = { '0': '未开始', '10': '进行中', '20': '已完工', '30': '已取消' };

function displayStatus(value: unknown): string {
  const key = String(value ?? '').trim();
  return STATUS_LABELS[key] || localizeBlacklakeText(value);
}

function nestedEvidenceLine(key: string, item: Record<string, unknown>): string {
  const parts: string[] = [];
  const text = (field: string, label: string, preserve = false) => {
    if (!(field in item) || item[field] === null || item[field] === undefined || item[field] === '') return;
    parts.push(`${label} ${preserve ? String(item[field]) : localizeBlacklakeText(item[field])}`);
  };
  const number = (field: string, label: string) => {
    if (!(field in item)) return;
    parts.push(`${label} ${formatBlacklakeNumber(item[field])}`);
  };
  text('material', '材料', true);
  text('materialCode', '物料编码', true);
  text('process', '工序', true);
  text('orderCode', '领退料单编码', true);
  text('projectCode', '工单编码', true);
  text('product', '产品', true);
  text('productCode', '产品编码', true);
  if ('status' in item) parts.push(`状态 ${displayStatus(item.status)}`);
  text('unit', '单位', true);
  number('netConsumption', '净消耗');
  number('quantity', '数量');
  number('sourceUnitQty', '来源单位用量');
  number('unitPrice', '来源单价');
  number('recognizedCost', '来源金额');
  number('hours', '累计工时');
  number('laborCost', '人工累计');
  if (key === 'processes') number('taskCount', '任务数');
  return parts.join(' · ') || '已提供结构化证据，页面不展开内部编码';
}

const NESTED_EVIDENCE_KEYS = new Set(['lines', 'materialLinesDetail', 'processes', 'materials']);
const BUSINESS_TEXT_KEYS = new Set(['material', 'materialCode', 'orderCode', 'product', 'productCode', 'projectCode', 'unit', 'baselineProject', 'comparisonProject']);
const PERCENT_KEYS = new Set(['threshold', 'spread', 'materialShare', 'laborShare']);

export function readableEvidenceEntries(evidence: Record<string, unknown> | null | undefined): { label: string; value: string }[] {
  if (!evidence) return [];
  return Object.entries(evidence).flatMap(([key, raw]) => {
    if (/id$/i.test(key)) return [];
    const label = localizeBlacklakeField(key);
    if (raw === null || raw === undefined || raw === '') return [{ label, value: '未提供' }];
    if (key === 'status') return [{ label, value: displayStatus(raw) }];
    if (PERCENT_KEYS.has(key) && typeof raw === 'number') return [{ label, value: `${formatBlacklakeNumber(raw * 100)}%` }];
    if (typeof raw === 'number') return [{ label, value: formatBlacklakeNumber(raw) }];
    if (typeof raw === 'boolean') return [{ label, value: raw ? '是' : '否' }];
    if (typeof raw === 'string') return isOpaqueIdentifier(raw) ? [] : [{ label, value: BUSINESS_TEXT_KEYS.has(key) ? raw : localizeBlacklakeText(raw) }];
    if (NESTED_EVIDENCE_KEYS.has(key) && Array.isArray(raw)) {
      return raw.flatMap(item => typeof item === 'object' && item !== null && !Array.isArray(item)
        ? [{ label, value: nestedEvidenceLine(key, item as Record<string, unknown>) }]
        : []);
    }
    if (Array.isArray(raw)) {
      const values = raw.map(item => typeof item === 'string' ? key === 'names' ? item : localizeBlacklakeText(item) : typeof item === 'number' ? formatBlacklakeNumber(item) : '').filter(Boolean);
      return values.length ? [{ label, value: values.join('、') }] : [];
    }
    return [{ label, value: '已提供结构化证据，页面不展开内部编码' }];
  });
}
