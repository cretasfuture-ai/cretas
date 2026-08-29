import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 账实红线 (owner 2026-08-20):「入库任务与批次」的编辑弹窗此前能直接改数量/总重量/总价值/
 * 原料类型/供应商 —— prod 实测普通仓储主管正好带 warehouse=rw / inventory=rw, 也就是说
 * 不需要任何特权角色就能改写库存台账, 且不留调整痕迹。
 *
 * owner 原话: "…包括所有其他权限、财务、工厂总监, 都应该是到盘点与期初库存去盘点的时候
 * 才能改, 不能够直接编辑批次。"
 *
 * ⚠️ 本闸只守前端这一半。另一半 (后端, 对所有角色生效) 在
 * `MaterialBatchServiceImplApiRoleGateTest` —— 只改前端等于没改, 接口还在。
 */

const RAW = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/materials/list.vue'),
  'utf8',
);

/**
 * ⛔ 必须先剥注释再扫。本仓 2026-08-20 刚踩过一晚四次「源码闸把我自己写的注释当成被测对象」:
 * 下面这些断言要找的字面量 (`...formData`、字段名) 在本文件的说明性注释里**原样出现过**,
 * 不剥注释的话闸会匹配到注释, 既造假阳性也造假阴性。
 */
function stripComments(source: string): string {
  return source
    .replace(/<!--[\s\S]*?-->/g, '')     // template 注释
    .replace(/\/\*[\s\S]*?\*\//g, '')    // 块注释 (含 JSDoc)
    .replace(/^\s*\/\/.*$/gm, '');       // 整行行注释
}

const SOURCE = stripComments(RAW);

/** 账实字段: 改了就直接改写库存台账 / 成本 / 溯源归属 / 消耗顺序。 */
const LOCKED_FIELDS = [
  'receiptQuantity',
  'quantityUnit',
  'totalWeight',
  'totalValue',
  'materialTypeId',
  'supplierId',
  // 2026-08-20 第二轮纳入: 入库日期是 FIFO/FEFO 的排序键与期间归属, 不是描述。
  // 上一轮不锁它的理由是"盘点改不了日期, 锁了是 dead-end" —— 成立,
  // 所以本轮**锁 + 修**一起做, 正路见下面「更正入库日期」那组断言。
  'receiptDate',
] as const;

/** 描述性信息: 仓管随手补录是合理的。 */
const EDITABLE_FIELDS = [
  'boxCount',
  'expireDate',
  'notes',
  'factoryNumber',
  'originPlace',
] as const;

/** 从源码里取出白名单数组的内容 (只取被 `as const` 收尾的那个声明)。 */
function editableWhitelistFromSource(): string[] {
  const match = SOURCE.match(/const EDITABLE_FIELDS_ON_UPDATE = \[([\s\S]*?)\] as const;/);
  // 前提断言: 抓不到就抛, 否则下面的循环空转而假绿 (本仓「变异不红的第五种成因」)
  expect(match, '没抓到 EDITABLE_FIELDS_ON_UPDATE 白名单声明 —— 本闸的解析器坏了, '
    + '后面每一条断言都会因为集合为空而恒真').not.toBeNull();
  return [...match![1].matchAll(/'([a-zA-Z]+)'/g)].map((m) => m[1]);
}

describe('批次编辑不再允许直接改账实数量 (owner 2026-08-20 内控)', () => {
  it('编辑提交走白名单, 不再整份 formData 发上去', () => {
    // 洞本身: `payload = { ...formData }` 连数量/总重量/总价值一起 PUT 上去。
    expect(SOURCE).toContain('buildUpdatePayload()');
    expect(SOURCE).toMatch(
      /put\(`\/\$\{factoryId\.value\}\/material-batches\/\$\{editingId\.value\}`, buildUpdatePayload\(\)\)/,
    );
  });

  it('白名单里一个账实字段都没有', () => {
    const whitelist = editableWhitelistFromSource();
    expect(whitelist.length, '白名单解析成了空数组').toBeGreaterThan(0);

    const leaked = LOCKED_FIELDS.filter((field) => whitelist.includes(field));
    expect(leaked, `账实字段漏进了编辑白名单: ${leaked.join(', ')} —— `
      + '这些必须走盘点与期初库存, 不能从编辑弹窗直接改').toEqual([]);
  });

  it('白名单确实覆盖了描述性字段 (阳性对照: 不是靠把白名单清空来变绿的)', () => {
    const whitelist = editableWhitelistFromSource();
    const missing = EDITABLE_FIELDS.filter((field) => !whitelist.includes(field));
    expect(missing, `描述性字段被误锁: ${missing.join(', ')} —— `
      + '仓管补录厂号/产地/备注/箱数是合理的, 锁掉它们是过度收权').toEqual([]);
  });

  /**
   * ⚠️ `date-picker` 是 2026-08-20 第二轮补进这个正则的。此前它只扫
   * input-number / select —— 而入库日期用的是 el-date-picker, 也就是说
   * **就算它没被 disable, 这条断言也读不到**。量错了控件类型 = 量了个寂寞。
   */
  const CONTROL_RE = /<el-(?:input-number|select|date-picker)[^>]*v-model="formData\.[^"]+"[^>]*>/g;

  it('账实字段在编辑态下是只读的', () => {
    // 界面层的第二道: 光不发还不够, 输入框还开着会让用户以为改成功了。
    const controls = SOURCE.match(CONTROL_RE) ?? [];
    expect(controls.length, '一个表单控件都没匹配到 —— 解析器坏了').toBeGreaterThan(3);

    // 前提断言: 每一个被锁的字段都得在这堆控件里出现过, 否则"没有未锁的"是因为没扫到它。
    // ⚠️ 实测 (2026-08-20 变异 M6b): 用第一轮那版不含 date-picker 的正则,
    // 把入库日期的 :disabled 整个拿掉, 这个 spec **15/15 全绿** —— 缺陷就在眼前而闸看不见。
    const notRendered = LOCKED_FIELDS.filter(
      (field) => !controls.some((tag) => tag.includes(`formData.${field}"`)),
    );
    expect(notRendered, `这些账实字段的控件一个都没被正则扫到: ${notRendered.join(', ')} —— `
      + '解析器漏了某种控件类型(例如 el-date-picker), 下面那条断言对它们恒真').toEqual([]);

    const unlocked = controls
      .filter((tag) => LOCKED_FIELDS.some((field) => tag.includes(`formData.${field}"`)))
      .filter((tag) => !tag.includes(':disabled="!!editingId"'));
    expect(unlocked, `这些账实字段在编辑态下仍可输入:\n${unlocked.join('\n')}`).toEqual([]);
  });

  it('描述性字段在编辑态下仍然可输入 (阴性对照: 不是把整个弹窗 disable 掉)', () => {
    const controls = SOURCE.match(CONTROL_RE) ?? [];
    const wronglyLocked = controls
      .filter((tag) => EDITABLE_FIELDS.some((field) => tag.includes(`formData.${field}"`)))
      .filter((tag) => tag.includes(':disabled="!!editingId"'));
    expect(wronglyLocked, `描述性字段被误锁:\n${wronglyLocked.join('\n')}`).toEqual([]);
  });

  it('只读不是 dead-end —— 同时给出"那去哪改"', () => {
    // 防呆 Rule 5: 只 disable 不说去哪, 用户会以为系统坏了。
    expect(SOURCE).toContain('goStocktake');
    expect(SOURCE).toContain("router.push('/warehouse/stocktakes')");
    expect(SOURCE).toContain('去盘点与期初库存');
  });
});

/**
 * 入库日期的正路。⛔ 这一组不是"锦上添花" —— 没有它, 上面那条锁就是在造死路:
 * 全仓只有本端点能改 receiptDate (adjust 只动数量, 盘点 apply 写的也是数量差异)。
 */
describe('入库日期: 锁了必须同时给正路 (owner 2026-08-20 第二轮)', () => {
  it('有一条真的会发出去的更正请求, 且带 reason', () => {
    expect(SOURCE).toMatch(
      /post\(\s*`\/\$\{factoryId\.value\}\/material-batches\/\$\{batchId\}\/receipt-date-correction`,\s*\{\s*receiptDate: newDate,\s*reason\s*\},?\s*\)/,
    );
  });

  it('原因必填, 且限长与后端 @Size(max=255) 一致', () => {
    expect(SOURCE).toContain('const CORRECTION_REASON_MAX_LENGTH = 255');
    // 空原因当场拦下, ⛔ 不是发上去让后端 400
    expect(SOURCE).toContain("if (!reason) { ElMessage.warning('请填写更正原因，它会写入审计记录'); return; }");
    // 输入框自己也要有硬上限, 否则用户打完 300 字才被告知超长
    expect(SOURCE).toContain(':maxlength="CORRECTION_REASON_MAX_LENGTH"');
  });

  it('入库日期的出口**不是**盘点 —— 盘点改不了日期, 指过去是把死路换成更长的死路', () => {
    expect(SOURCE).toContain('更正入库日期');
    expect(SOURCE).toContain('openCorrectReceiptDate');
    // 阴性对照: 那句例外说明必须明说"盘点改不了它"
    expect(SOURCE).toContain('盘点改不了它');
  });

  it('没资格的人看到的是"该找谁", 不是一个点下去必 403 的按钮', () => {
    expect(SOURCE).toContain('canCorrectReceiptDate');
    expect(SOURCE).toMatch(/v-if="canCorrectReceiptDate"/);
    expect(SOURCE).toMatch(/v-else[^>]*>[\s\S]{0,200}需财务经理或工厂管理员更正/);
  });

  it('角色档位与后端 RECEIPT_DATE_CORRECTION_ROLES 逐字一致 (两份会漂, 钉住它)', () => {
    const match = SOURCE.match(/const RECEIPT_DATE_CORRECTION_ROLES = \[([\s\S]*?)\];/);
    expect(match, '没抓到前端的角色档位声明 —— 解析器坏了').not.toBeNull();
    const roles = [...match![1].matchAll(/'([a-z_]+)'/g)].map((m) => m[1]);
    // 与 MaterialBatchServiceImpl.RECEIPT_DATE_CORRECTION_ROLES / 盘点审批同一档。
    // 后端那侧另有一条 Java 闸钉它与盘点审批相等, 两条合起来把三份钉成一份。
    expect(roles.sort()).toEqual(['factory_super_admin', 'finance_manager', 'platform_admin']);
  });

  it('未来日期在填之前就被挡住 (防呆 Rule 1: 边界先展示, 不是提交后才报错)', () => {
    expect(SOURCE).toContain(':disabled-date="disableFutureDate"');
    expect(SOURCE).toContain('不能晚于今天');
  });

  it('新增批次不受影响 (POST 仍然要填数量, 否则建不出批次)', () => {
    // 阴性对照: 若把锁做成"整个弹窗只读", 这条会红。
    expect(SOURCE).toMatch(/post\(`\/\$\{factoryId\.value\}\/material-batches`, payload\)/);
    expect(SOURCE).toContain('const payload = { ...formData };');
  });
});

describe('批次估值: 锁了必须走带新旧值审计的正路', () => {
  it('更正请求只提交单价与原因，不借编辑批次接口绕过账实红线', () => {
    expect(SOURCE).toMatch(
      /post\(`\/\$\{factoryId\.value\}\/material-batches\/\$\{batchId\}\/cost-correction`,\s*\{\s*unitPrice:\s*price,\s*reason,?\s*\}\)/,
    );
    expect(SOURCE).toContain('更正批次估值');
    expect(SOURCE).toContain('已记录新旧单价、原因和操作人');
  });

  it('空原因、非正价格与未变化价格都在发送前拦截', () => {
    expect(SOURCE).toContain("if (!Number.isFinite(price) || price <= 0)");
    expect(SOURCE).toContain("ElMessage.warning('估值单价没有变化，无需更正')");
    expect(SOURCE).toContain("if (!reason) { ElMessage.warning('请填写估值依据，它会写入审计台账'); return; }");
  });
});

describe('批次状态中文取自 enumDisplay, 不在页面里写死', () => {
  it('用的是 MATERIAL_BATCH_STATUS_LABELS 而不是本文件的私有表', () => {
    expect(SOURCE).toContain("import { enumLabel, MATERIAL_BATCH_STATUS_LABELS } from '@/utils/enumDisplay'");
    expect(SOURCE).toContain('enumLabel(status?.toUpperCase(), MATERIAL_BATCH_STATUS_LABELS)');
  });

  it('页面里不再有写死的批次状态中文表', () => {
    // 原来是 getStatusText 里一份只有 4 个码的 map, 剩下 8 个取值原样吐英文 (客户看到 INSPECTING)。
    expect(SOURCE).not.toContain("AVAILABLE: '可用'");
    expect(SOURCE).not.toContain("EXPIRED: '已过期'");
  });
});
