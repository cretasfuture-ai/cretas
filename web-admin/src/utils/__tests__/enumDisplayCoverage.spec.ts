import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  APPROVAL_INSTANCE_STATUS_LABELS,
  COMMON_ENUM_LABELS,
  FINISHED_GOODS_BATCH_STATUS_LABELS,
  INTERNAL_TRANSFER_STATUS_LABELS,
  MATERIAL_BATCH_STATUS_LABELS,
  MATERIAL_REQUISITION_STATUS_LABELS,
  PAYMENT_REQUEST_STATUS_LABELS,
  PRODUCTION_BATCH_STATUS_LABELS,
  PRODUCTION_PLAN_STATUS_LABELS,
  PRODUCTION_SETTLEMENT_POSTING_STATUS_LABELS,
  PURCHASE_INVOICE_RECONCILE_STATUS_LABELS,
  PURCHASE_ORDER_INVOICE_STATUS_LABELS,
  PURCHASE_ORDER_STATUS_LABELS,
  PURCHASE_RECEIVE_STATUS_LABELS,
  RETURN_ORDER_STATUS_LABELS,
  ROLE_LABELS,
  SALES_DELIVERY_STATUS_LABELS,
  SALES_ORDER_STATUS_LABELS,
  SETTLEMENT_TYPE_LABELS,
  TRACE_STATUS_LABELS_BY_DOCUMENT_TYPE,
  TRANSFER_DIFF_STATUS_LABELS,
  enumLabel,
  traceStatusLabel,
} from '../enumDisplay';

/**
 * 客户 2026-07-30 (Sheet Row 6): 单据追踪抽屉显示「未知状态（IN_PROGRESS）」。
 *
 * 光补一个码没用 —— 下次后端加枚举值还会漏。本测试**直接读 Java 枚举源文件**断言覆盖,
 * 与 `EnumCheckConstraintDriftTest` 挡 DB CHECK 漂移是同一招: 让"忘了同步"在 CI 就红,
 * 而不是等客户看到英文码。
 */

const JAVA_ROOT = resolve(__dirname, '../../../../backend/java/cretas-api/src/main/java/com/cretas/aims');

function readJava(relative: string): string {
  const path = resolve(JAVA_ROOT, relative);
  // 刻意不 skip: 后端重命名/移动文件时必须让这条测试红, 静默跳过等于门禁消失。
  expect(existsSync(path), `找不到 ${relative} —— 后端文件被移动或改名了, 请同步本测试的路径`).toBe(true);
  return readFileSync(path, 'utf8');
}

const ENUM_CONSTANT_RE = /^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|$)/gm;

/**
 * 去掉 Java 源码里的注释 (`//` 行注释与 `/* ... *``/` 块注释, 含 javadoc `/** ... *``/`),
 * 保留字符串字面量原文不动、换行原样保留 (被删内容用空格占位, 不删换行本身) ——
 * 后面按行匹配常量名的正则依赖行结构, 删掉换行会把两行意外拼接。
 *
 * 走字符级状态机而不是正则的原因: 字符串字面量里可能出现 `//` / `/*` (Java 允许),
 * 正则很容易把这些误判成注释边界；状态机能精确知道"现在在字符串里"还是"在注释里",
 * 两者互不干扰 —— 进入字符串状态后, 注释探测分支整体不生效。
 *
 * 2026-09-04 实测: SalesOrderStatus.java 新增 SHORT_CLOSED 前那段 javadoc 差一点写进
 * 一个半角分号 —— 旧解析器 `body.split(';')[0]` 会把那个分号当成"常量区结束",
 * SHORT_CLOSED 连同它之后的字段/构造器一起从解析结果里消失, 而断言仍然全绿
 * (循环体压根没跑到它, 见 measurement-and-wiring.md 形态 A⁗ 第 6 例同形)。
 */
function stripJavaComments(source: string): string {
  let out = '';
  let inString = false;
  let inLineComment = false;
  let inBlockComment = false;
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    const c2 = i + 1 < source.length ? source[i + 1] : '';
    if (inLineComment) {
      if (c === '\n') { inLineComment = false; out += c; } else { out += ' '; }
      continue;
    }
    if (inBlockComment) {
      if (c === '*' && c2 === '/') { inBlockComment = false; out += '  '; i++; }
      else { out += c === '\n' ? '\n' : ' '; }
      continue;
    }
    if (inString) {
      out += c;
      // 反斜杠转义 (如 `\"`) 要连同下一字符一起跳过, 否则会误判字符串提前收尾。
      if (c === '\\' && c2) { out += c2; i++; continue; }
      if (c === '"') inString = false;
      continue;
    }
    if (c === '/' && c2 === '/') { inLineComment = true; out += '  '; i++; continue; }
    if (c === '/' && c2 === '*') { inBlockComment = true; out += '  '; i++; continue; }
    if (c === '"') { inString = true; out += c; continue; }
    out += c;
  }
  return out;
}

/**
 * 在 (已去注释的) 源码里找到第一个"不在字符串字面量里"的 `terminator` (`;` 或 `}`),
 * 返回它之前的子串；找不到就返回整个字符串 (与旧代码 `split(';')[0]` 在没有 `;` 时同义,
 * 例如 PurchaseInvoiceStatus 没有字段/构造器、枚举体里根本没有 `;`)。
 *
 * 单独存在的理由: `stripJavaComments` 只清掉了注释, 字符串字面量原样保留 —— 如果构造器
 * 参数里写了 `FOO("a;b")` 这样的字面量, 朴素 `split(';')` 仍会把字面量内部这个分号当成
 * 常量区终点, 把 FOO 之后的常量一并吃掉。这里再做一次字符串感知的扫描来避开它。
 * 本仓当前 12 个顶层枚举没有这种取值 (已逐个人工核对源文件, 全部用全角标点或干净短语),
 * 但解析器的正确性不应该依赖"当前没有" —— 见下方 fixture 测试, 这条分支是可复现的。
 *
 * ⚠️ 只处理双引号字符串 (Java `char` 单引号字面量): 这 12 个枚举的构造器参数清一色是
 * `String`, 不会出现 `'x'` 这种写法, 加这层没有意义的复杂度不做。
 */
function sliceBeforeBareChar(source: string, terminator: string): string {
  let inString = false;
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    if (inString) {
      if (c === '\\') { i++; continue; }
      if (c === '"') inString = false;
      continue;
    }
    if (c === '"') { inString = true; continue; }
    if (c === terminator) return source.slice(0, i);
  }
  return source;
}

/** 取顶层 enum 的常量名 (常量在 `;` 之前, 之后是字段/构造器)。 */
function topLevelEnumConstants(source: string, enumName: string): string[] {
  const start = source.indexOf(`enum ${enumName}`);
  expect(start, `${enumName} 声明找不到`).toBeGreaterThan(-1);
  const rawBody = source.slice(source.indexOf('{', start) + 1);
  const head = sliceBeforeBareChar(stripJavaComments(rawBody), ';');
  return [...head.matchAll(ENUM_CONSTANT_RE)].map((m) => m[1]);
}

/** 取嵌套 enum 的常量名 (无构造器时常量列表到 `}` 为止)。 */
function nestedEnumConstants(source: string, enumName: string): string[] {
  const start = source.indexOf(`enum ${enumName}`);
  expect(start, `嵌套 enum ${enumName} 声明找不到`).toBeGreaterThan(-1);
  const open = source.indexOf('{', start);
  const rest = source.slice(open + 1);
  // 同一形状: `}` 也可能落在注释或字符串字面量里, 与顶层解析走同一套安全边界。
  const body = sliceBeforeBareChar(stripJavaComments(rest), '}');
  return [...body.matchAll(ENUM_CONSTANT_RE)].map((m) => m[1]);
}

/**
 * 复刻 SalesOrderStatus.java 里 SHORT_CLOSED 前那段 javadoc 的形状: 常量前面隔着一段
 * 含中文的块注释, 注释正文里混进一个半角分号 —— 旧解析器 `body.split(';')[0]` 会把这个
 * 分号当成"常量区结束", AFTER_COMMENT 连同它之后的字段/构造器一起从解析结果里消失。
 */
const COMMENT_SEMICOLON_FIXTURE = `
public enum FixtureStatus {
    BEFORE("之前"),
    /**
     * 这段说明故意混进一个半角分号; 用来验证解析器不会被它切断。
     */
    AFTER_COMMENT("注释之后");

    private final String displayName;

    FixtureStatus(String displayName) {
        this.displayName = displayName;
    }
}
`;

/**
 * 复刻"构造器参数字符串字面量里带分号"的形状: `FOO("a;b")` —— 只去掉注释还不够,
 * 朴素 `split(';')` 会被字面量内部这个分号切断, 同样会漏掉它之后的常量。
 */
const STRING_SEMICOLON_FIXTURE = `
public enum FixtureStatus2 {
    HAS_SEMICOLON_IN_STRING("含分号;的取值"),
    AFTER_STRING("字符串之后");

    private final String displayName;

    FixtureStatus2(String displayName) {
        this.displayName = displayName;
    }
}
`;

describe('解析器对"注释里藏分号 / 字符串里藏分号"具备抵抗力 (2026-09-04)', () => {
  it('常量在"含分号的注释"之后仍能被解析出来', () => {
    const values = topLevelEnumConstants(COMMENT_SEMICOLON_FIXTURE, 'FixtureStatus');
    // 先证明"解析器真的看到了什么", 再判断红绿 —— 见 measurement-and-wiring.md 硬约束/形态 C″:
    // 变异不红时先证明变异到达了被测代码, 不要直接从"没红"跳到"断言在守空气"。
    // eslint-disable-next-line no-console
    console.log('PARSER_EXTRACTED(comment-fixture):', JSON.stringify(values));
    expect(values).toEqual(['BEFORE', 'AFTER_COMMENT']);
  });

  it('常量在"构造器参数字符串字面量里带分号"之后仍能被解析出来', () => {
    const values = topLevelEnumConstants(STRING_SEMICOLON_FIXTURE, 'FixtureStatus2');
    // eslint-disable-next-line no-console
    console.log('PARSER_EXTRACTED(string-fixture):', JSON.stringify(values));
    expect(values).toEqual(['HAS_SEMICOLON_IN_STRING', 'AFTER_STRING']);
  });
});

const ENUM_CASES: Array<{
  label: string;
  constants: () => string[];
  map: Record<string, string>;
  /**
   * 「解析器没坏」那条前提断言的下界。默认 4 —— 原来是写死的 `toBeGreaterThan(3)`。
   *
   * 2026-08-20 加这个字段是因为 `PurchaseInvoiceStatus` 一共只有 3 个常量, 写死的下界
   * 会把一条**正确**的用例判红。⛔ 它不是用来放松门禁的: 每个 case 都要填一个**贴着
   * 该枚举当前常量数**的下界, 解析器返回空数组时照样红。
   */
  minConstants?: number;
}> = [
  {
    label: 'SalesOrderStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/SalesOrderStatus.java'), 'SalesOrderStatus'),
    map: SALES_ORDER_STATUS_LABELS,
  },
  {
    label: 'PurchaseOrderStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/PurchaseOrderStatus.java'), 'PurchaseOrderStatus'),
    map: PURCHASE_ORDER_STATUS_LABELS,
  },
  {
    label: 'PurchaseReceiveStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/PurchaseReceiveStatus.java'), 'PurchaseReceiveStatus'),
    map: PURCHASE_RECEIVE_STATUS_LABELS,
  },
  {
    label: 'ProductionBatchStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/ProductionBatchStatus.java'), 'ProductionBatchStatus'),
    map: PRODUCTION_BATCH_STATUS_LABELS,
  },
  {
    label: 'SalesDeliveryStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/SalesDeliveryStatus.java'), 'SalesDeliveryStatus'),
    map: SALES_DELIVERY_STATUS_LABELS,
  },
  {
    label: 'FactoryMaterialRequisition.Status',
    constants: () => nestedEnumConstants(readJava('entity/factory/FactoryMaterialRequisition.java'), 'Status'),
    map: MATERIAL_REQUISITION_STATUS_LABELS,
  },
  // ↓ 2026-07-30: 单据追踪扩到销售/采购/调拨后新增的四个枚举 (同样对着 Java 源文件断言)
  {
    label: 'ProductionPlanStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/ProductionPlanStatus.java'), 'ProductionPlanStatus'),
    map: PRODUCTION_PLAN_STATUS_LABELS,
  },
  {
    label: 'TransferStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/TransferStatus.java'), 'TransferStatus'),
    map: INTERNAL_TRANSFER_STATUS_LABELS,
  },
  {
    label: 'ReturnOrderStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/ReturnOrderStatus.java'), 'ReturnOrderStatus'),
    map: RETURN_ORDER_STATUS_LABELS,
  },
  {
    label: 'PaymentRequestStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/PaymentRequestStatus.java'), 'PaymentRequestStatus'),
    map: PAYMENT_REQUEST_STATUS_LABELS,
  },
  {
    // 2026-08-20 (owner 入库任务与批次反馈): 状态列显示成 `INSPECTING`。
    // 该页原本自带一份只有 4 个码的私有表, 12 个取值里 8 个直接吐英文。
    label: 'MaterialBatchStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/MaterialBatchStatus.java'), 'MaterialBatchStatus'),
    map: MATERIAL_BATCH_STATUS_LABELS,
  },
  // ↓ 2026-08-20 (owner 采购订单详情页反馈): 这三个枚举一个都没进过门禁, 于是
  //   「OA 状态: 未知状态（RUNNING）」「发票状态: 未知状态（NOT_RECEIVED）」在 prod 挂了很久。
  {
    label: 'ApprovalWorkflowInstance.InstanceStatus',
    // 嵌套 enum —— 没有独立文件, 搜 WorkflowInstanceStatus.java 是搜不到的。
    constants: () => nestedEnumConstants(readJava('entity/workflow/ApprovalWorkflowInstance.java'), 'InstanceStatus'),
    map: APPROVAL_INSTANCE_STATUS_LABELS,
  },
  {
    label: 'PurchaseInvoiceStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/PurchaseInvoiceStatus.java'), 'PurchaseInvoiceStatus'),
    map: PURCHASE_ORDER_INVOICE_STATUS_LABELS,
    // 该枚举当前恰好 3 个常量, 下界贴死 —— 解析器返回空数组仍然会红。
    minConstants: 3,
  },
  {
    label: 'SettlementType',
    constants: () => topLevelEnumConstants(readJava('entity/enums/SettlementType.java'), 'SettlementType'),
    map: SETTLEMENT_TYPE_LABELS,
  },
];

describe('枚举中文覆盖 (对着 Java 源文件断言)', () => {
  it.each(ENUM_CASES)('$label 的每个常量都有中文', ({ label, constants, map, minConstants }) => {
    const values = constants();
    // 解析器坏掉会让循环空转而假绿 —— 先钉住"确实解析出了东西"
    expect(values.length, `${label} 一个常量都没解析出来, 大概率是本测试的解析器坏了`)
      .toBeGreaterThanOrEqual(minConstants ?? 4);

    const missing = values.filter((code) => !map[code]);
    expect(missing, `${label} 缺中文: ${missing.join(', ')} —— 后端加了枚举值但前端没补, `
      + '客户会看到「未知状态（CODE）」。补到 enumDisplay.ts 对应的分域表里。').toEqual([]);
  });

  it('单据追踪抽屉的每种 documentType 都配了状态表', () => {
    // key 必须与后端两个 trace service 的 document(type, …) 字面量一致。
    // 2026-07-30 起追踪扩到销售/采购/调拨, 第二个 service 也必须纳入门禁, 否则新单据类型
    // 加了没补中文这条测试照样绿。
    const sources = [
      readJava('service/production/ProductionDocumentTraceService.java'),
      readJava('service/trace/BusinessDocumentTraceService.java'),
    ];
    const types = sources.flatMap(
      (backend) => [...backend.matchAll(/document\(\s*"([A-Z_]+)"/g)].map((m) => m[1]),
    );
    expect(types.length, 'documentType 一个都没解析出来').toBeGreaterThan(3);
    // 锚点自身的类型也要有状态表 (抽屉顶部那行也渲染中文)
    const anchorTypes = sources.flatMap(
      (backend) => [...backend.matchAll(/anchorType\("([A-Z_]+)"\)/g)].map((m) => m[1]),
    );
    expect(anchorTypes.length, 'anchorType 一个都没解析出来').toBeGreaterThan(2);

    const unmapped = [...new Set([...types, ...anchorTypes])]
      .filter((t) => !TRACE_STATUS_LABELS_BY_DOCUMENT_TYPE[t]);
    expect(unmapped, `这些单据类型没有状态表, 会退回全局表并可能显示错的中文: ${unmapped.join(', ')}`)
      .toEqual([]);
  });

  it('String 型状态列也覆盖到 (它们不是枚举, 取值来自后端 setter)', () => {
    // production_settlements.posting_status —— 实体默认值 + 所有 setPostingStatus("…")
    const settlement = readJava('entity/ProductionSettlement.java');
    expect(settlement).toContain('PENDING_POSTING');
    for (const code of ['PENDING_POSTING', 'PENDING_CLEARING', 'PENDING_WAREHOUSE_RECEIPT', 'POSTED', 'POSTED_WITH_TOLERANCE']) {
      expect(PRODUCTION_SETTLEMENT_POSTING_STATUS_LABELS[code], `postingStatus 缺 ${code}`).toBeTruthy();
    }
    for (const code of ['AVAILABLE', 'DEFECTIVE', 'DEPLETED', 'PENDING_INSPECTION']) {
      expect(FINISHED_GOODS_BATCH_STATUS_LABELS[code], `成品批次状态缺 ${code}`).toBeTruthy();
    }
    // transfer_diff_records.status —— 实体默认值 "PENDING" + TransferDiffServiceImpl 的两个 setStatus
    const diff = readJava('entity/inventory/TransferDiffRecord.java');
    expect(diff).toContain('private String status = "PENDING"');
    for (const code of ['PENDING', 'RESOLVED']) {
      expect(TRANSFER_DIFF_STATUS_LABELS[code], `调拨差异状态缺 ${code}`).toBeTruthy();
    }
    // purchase_invoices.reconcile_status —— 取值写在实体的行内注释里
    const invoice = readJava('entity/inventory/PurchaseInvoice.java');
    expect(invoice).toContain('PENDING / MATCHED / MISMATCHED');
    for (const code of ['PENDING', 'MATCHED', 'MISMATCHED']) {
      expect(PURCHASE_INVOICE_RECONCILE_STATUS_LABELS[code], `采购发票对账状态缺 ${code}`).toBeTruthy();
    }
  });

  it('同码在新加的单据里也没有被全局表压平', () => {
    // PENDING: 付款申请是「待财务初审」, 采购发票是「待对账」, 全局表是「待处理」
    expect(traceStatusLabel('PAYMENT_REQUEST', 'PENDING')).toBe('待财务初审');
    expect(traceStatusLabel('PURCHASE_INVOICE', 'PENDING')).toBe('待对账');
    expect(enumLabel('PENDING')).toBe('待处理');
    // FINANCE_APPROVED: 退货单是「财务已审」, 全局表是「财务已审核」
    expect(traceStatusLabel('SALES_RETURN', 'FINANCE_APPROVED')).toBe('财务已审');
    // PREPARED 只在生产计划里出现且是「草稿」—— 全局表根本没有这个码
    expect(traceStatusLabel('PRODUCTION_PLAN', 'PREPARED')).toBe('草稿');
    // 调拨的 RECEIVED 是「已签收」
    expect(traceStatusLabel('INTERNAL_TRANSFER', 'RECEIVED')).toBe('已签收');
  });

  it('客户实际撞到的那个码在生产批次语境下是「生产中」不是「进行中」', () => {
    expect(traceStatusLabel('PRODUCTION_BATCH', 'IN_PROGRESS')).toBe('生产中');
    // 全局表仍给一个中性译法, 兜住没有分域表的场景
    expect(enumLabel('IN_PROGRESS')).toBe('进行中');
    expect(enumLabel('IN_PROGRESS')).not.toContain('未知状态');
  });

  it('owner 2026-08-20 在采购订单详情页撞到的两个码不再兜底成英文', () => {
    // 这两条是「产品可见文案」层的断言 —— 上面的覆盖用例只保证表里**有**这个 key,
    // 不保证页面把码喂给了正确的那张表 (那正是当初漏掉的那一环)。
    expect(enumLabel('RUNNING', APPROVAL_INSTANCE_STATUS_LABELS)).toBe('审批中');
    expect(enumLabel('NOT_RECEIVED', PURCHASE_ORDER_INVOICE_STATUS_LABELS)).toBe('未收票');
    expect(enumLabel('RUNNING', APPROVAL_INSTANCE_STATUS_LABELS)).not.toContain('未知状态');
    expect(enumLabel('NOT_RECEIVED', PURCHASE_ORDER_INVOICE_STATUS_LABELS)).not.toContain('未知状态');
    // 阴性对照: 不喂分域表时它们**仍然**是未知码 —— 证明上面的绿来自分域表而不是
    // 「反正全局表也认识」。⛔ 不要为了让这两行变绿去把它们塞进 COMMON_ENUM_LABELS。
    expect(enumLabel('RUNNING')).toBe('未知状态（RUNNING）');
    expect(enumLabel('NOT_RECEIVED')).toBe('未知状态（NOT_RECEIVED）');
  });

  it('采购收票状态与采购发票对账状态是两张表, 没有互相顶替', () => {
    // 两者都有 key 叫得像, 但语义不同; 混用会让「未收票」显示成「待对账」。
    expect(PURCHASE_ORDER_INVOICE_STATUS_LABELS.RECONCILED).toBe('已对账');
    expect(PURCHASE_INVOICE_RECONCILE_STATUS_LABELS.MATCHED).toBe('对账一致');
    expect(PURCHASE_ORDER_INVOICE_STATUS_LABELS.PENDING).toBeUndefined();
  });

  it('结算方式的中文与后端 displayName 逐字一致 (它曾经是页面里的行内字面量)', () => {
    const source = readJava('entity/enums/SettlementType.java');
    // 直接对着 Java 的 displayName 断言, 而不是复制一份中文进来 —— 复制就又是两份了。
    const pairs = [...source.matchAll(/^\s*([A-Z_]+)\("([^"]+)"\)/gm)].map((m) => [m[1], m[2]]);
    expect(pairs.length, 'SettlementType 的 displayName 一对都没解析出来').toBeGreaterThanOrEqual(6);
    for (const [code, displayName] of pairs) {
      expect(SETTLEMENT_TYPE_LABELS[code], `结算方式 ${code} 的中文与后端不一致`).toBe(displayName);
    }
  });

  it('同码不同义靠分域表区分, 没有被全局表压平', () => {
    expect(traceStatusLabel('PURCHASE_RECEIPT', 'REJECTED')).toBe('已退回');
    expect(traceStatusLabel('SALES_ORDER', 'FINANCE_APPROVED')).toBe('财务已批准');
    expect(traceStatusLabel('PURCHASE_ORDER', 'FINANCE_APPROVED')).toBe('财务已审核');
  });

  it('角色码全部有中文 (审批人一栏原本只映射了 2 个角色)', () => {
    // 角色码的权威来源是 DB (users.role_code ∪ platform_role_permissions.role_code),
    // 代码里没有单一枚举可解析 —— 故此处钉住 2026-07-30 实测 prod 的全集。
    // 新增角色时同步这里和 ROLE_LABELS。
    const rolesInProd = [
      'cashier', 'department_admin', 'dispatcher', 'equipment_admin', 'factory_super_admin',
      'finance_manager', 'group_leader', 'hr_admin', 'operator', 'permission_admin',
      'platform_admin', 'procurement_manager', 'production_clerk', 'production_manager', 'quality_controller',
      'quality_inspector', 'quality_manager', 'restaurant_chef', 'restaurant_manager',
      'restaurant_owner', 'restaurant_purchaser', 'sales_manager', 'team_leader',
      'unactivated', 'viewer', 'warehouse_manager', 'warehouse_worker',
      'workshop_supervisor', 'yield_operator',
    ];
    const missing = rolesInProd.filter((role) => !ROLE_LABELS[role]);
    expect(missing, `角色缺中文: ${missing.join(', ')}`).toEqual([]);
    for (const role of rolesInProd) {
      expect(enumLabel(role), `${role} 渲染成了兜底文案`).not.toContain('未知状态');
    }
  });

  it('角色码 (小写) 与状态码 (大写) 不会互相串, 所以能同链兜底', () => {
    const roleKeys = Object.keys(ROLE_LABELS);
    const statusKeys = Object.keys(COMMON_ENUM_LABELS);
    expect(roleKeys.every((k) => k === k.toLowerCase())).toBe(true);
    expect(statusKeys.every((k) => k === k.toUpperCase())).toBe(true);
    expect(roleKeys.filter((k) => statusKeys.includes(k))).toEqual([]);
  });

  it('仍然把真正未知的码原样吐出来, 不假装认识 (禁降级)', () => {
    expect(enumLabel('SOME_BRAND_NEW_CODE')).toBe('未知状态（SOME_BRAND_NEW_CODE）');
    expect(enumLabel(null)).toBe('—');
    expect(enumLabel('')).toBe('—');
  });

  it('approval-workflow-editor 不再自带一份角色表 (曾有 4 份互相矛盾)', () => {
    const directory = readFileSync(
      resolve(__dirname, '../../views/platform/approval-workflow-editor/lib/approvalDirectory.ts'),
      'utf8',
    );
    expect(directory).toContain("import { ROLE_LABELS } from '@/utils/enumDisplay'");
    expect(directory).not.toContain('const FALLBACK_ROLE_LABELS');
    // 本页特有语义: 未登记的码是"历史配置", 不能退成「未知状态（…）」
    expect(directory).toContain("?? '历史审批角色'");
  });
});
