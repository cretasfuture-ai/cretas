import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 采购订单详情页 —— owner 2026-08-20 的验收口径。
 *
 * 🔴 这一页的读者是**财务**, 不是采购。owner 原话:「财务需要看到的跟实际采购需要看的
 * 不一样, 财务不需要看到的东西我就不给他看」。所以本文件里那些「某某不许出现」的断言,
 * 守的是**读者不需要**, ⛔ 不是「那个字段现在恰好没值」—— 后者将来会被数据推翻。
 *
 * ⚠️ 本文件是源码闸, 量的是模板里的**列定义 / 绑定**这类字面结构, 刻意不去 grep
 * 整篇文件的中文词 —— 那样会把本文件和 detail.vue 自己的注释也数进去 (本仓踩过)。
 */

const detailPath = resolve(__dirname, '..', 'detail.vue');
const rawSource = readFileSync(detailPath, 'utf8');

/**
 * 🔴 扫描前先剥注释。
 *
 * 本闸第一版没剥, 当场被自己咬: detail.vue 的注释里**原样引用**了那个不该再调用的
 * 接口路径, 于是 `not.toContain('receiving/receipts/by-order/')` 红了 —— 而代码里
 * 一次都没调它。本仓已经因为同一个形状踩过多次 (闸把「我自己写的注释」当被测对象),
 * 正解是**改尺子**, ⛔ 不是回去删注释迁就闸。
 *
 * 只剥注释, 不剥字符串字面量 —— 我们要量的正是代码里的字面量 (列定义、接口路径)。
 */
function stripComments(text: string): string {
  return text
    .replace(/<!--[\s\S]*?-->/g, '')            // HTML/模板注释
    .replace(/\/\*[\s\S]*?\*\//g, '')           // 块注释与 JSDoc
    .replace(/^[ \t]*\/\/.*$/gm, '');           // 整行的行注释 (⛔ 不动行内 `//`, 免得咬到 URL)
}

const source = stripComments(rawSource);

/** 前提断言: 尺子得先量得到东西。抓不到就抛, ⛔ 不静默返回空串。 */
function templateBlock(): string {
  const start = source.indexOf('<template>');
  const end = source.lastIndexOf('</template>');
  if (start < 0 || end < 0 || end <= start) {
    throw new Error('detail.vue 里找不到 <template> 块 —— 本闸的切片逻辑坏了, 后面的断言全部无意义');
  }
  return source.slice(start, end);
}

describe('采购订单详情页按「财务视角」裁剪 (owner 2026-08-20)', () => {
  it('前提: 剥注释的尺子既没剥过头也没漏剥, 且切得到 template', () => {
    // 阳性对照 —— 剥完之后代码还在。剥过头会让本文件所有 not.toContain 变成恒真式。
    expect(source).toContain('const pdfDownloading = ref(false)');
    expect(source).toContain('<el-descriptions');
    expect(source.length).toBeGreaterThan(rawSource.length * 0.5);

    // 阴性对照 —— 注释确实被剥掉了。detail.vue 的注释里原样引用了那个已停用的接口路径,
    // 本闸第一版就是被它咬红的; 这一行保证「剥注释」这件事真的发生了。
    expect(rawSource).toContain('receiving/receipts/by-order/');
    expect(source).not.toContain('receiving/receipts/by-order/');

    const template = templateBlock();
    expect(template.length).toBeGreaterThan(500);
    expect(template).toContain('明细');
    expect(template).toContain('el-table-column');
  });

  it('逐行的收货进度列不在这一页 (财务不需要看)', () => {
    // ⛔ 不要因为「将来这单收了货就有值了」把它加回来 —— 判据是读者不需要, 与有没有值无关。
    expect(templateBlock()).not.toContain('label="已收货"');
    expect(templateBlock()).not.toContain('row.receivedQuantity || 0');
  });

  it('箱数列去掉, 以规格为主', () => {
    const template = templateBlock();
    // owner:「每个原料都应该有自己的规格, 规格里说清 kg 怎么换算成箱/件, 不需要两列」
    expect(template).not.toContain('label="箱数"');
    expect(template).not.toContain('row.boxQuantity');
    // 阳性对照: 规格列必须还在, 否则上面两条会因为「两列都没了」而假绿
    expect(template).toContain('label="规格"');
    expect(template).toContain('row.specification');
  });

  it('单价按有效位显示, 不再拖四个零', () => {
    expect(source).toContain('formatUnitPrice(purchaseTaxIncludedUnitPrice(row.unitPrice, row.taxRate))');
    // 阴性对照: 定死 4 位小数的旧写法不许回来
    expect(source).not.toContain('Number(row.unitPrice || 0).toFixed(4)');
  });

  it('三价对比默认展开', () => {
    // owner:「这个事还是蛮重要的, 如果价格有误差…」——「-20.3% 异常」那种红行不能默认藏着。
    expect(source).toContain("const priceCollapseActive = ref<string[]>(['price'])");
    expect(templateBlock()).toContain('v-model="priceCollapseActive"');
    // 默认展开后 @change 不再是唯一的加载时机, 挂载时就得拉一次
    expect(source).toContain('if (canViewPrice.value) loadPriceComparison()');
  });

  it('收货状态读订单自己的字段, 不再靠收货记录数组的长度推断', () => {
    // 本单收货记录整块去掉后, 靠 receives.length 推断会连带失效 —— 两处必须一起处理。
    expect(source).toContain('receiptStateText(order)');
    expect(source).not.toContain('receives.length');
    expect(source).not.toContain('receives.value');
    // 本单收货明细的接口不该再被调用
    expect(source).not.toContain('receiving/receipts/by-order/');
    // ⛔ 后端没有 receiptStatus 这个字段 (全后端 grep 0 命中), 不许再写那条死分支
    expect(source).not.toContain('order.receiptStatus ?');
  });

  it('历史收货只留位置, ⛔ 不用本单接口硬凑', () => {
    const template = templateBlock();
    expect(template).toContain('历史');
    // 空态要说清「没接入」而不是「没有记录」—— 后者是假话
    expect(template).toContain('尚未接入');
    // ⑦ owner 说现在的行为没问题 ⇒ 这个入口保持原样
    expect(template).toContain('前往仓储收货任务');
  });

  it('页面上的状态码都喂给了对应的分域表, 没有裸用全局表', () => {
    expect(source).toContain('enumLabel(approvalProgress.status, APPROVAL_INSTANCE_STATUS_LABELS)');
    expect(source).toContain('enumLabel(order.invoiceStatus, PURCHASE_ORDER_INVOICE_STATUS_LABELS)');
    expect(source).toContain('enumLabel(order.settlementType, SETTLEMENT_TYPE_LABELS)');
    // 阴性对照: 结算方式那份行内字面量表不许回来 (它与后端 SettlementType 是两份, 必漂)
    expect(source).not.toContain("PREPAID: '预付'");
  });

  it('后端没下发的金额字段不许被兜底成 0', () => {
    // 应付/已付/未付 在后端实体上根本不存在。读不到就显示读不到。
    for (const field of ['payableAmount', 'paidAmount', 'unpaidAmount']) {
      expect(source).toContain(`order.${field} == null ? '-'`);
      expect(source).not.toContain(`order.${field} || 0`);
    }
  });
});

/**
 * 第二轮裁剪 (owner 2026-08-20 「其他都改一下吧」)。
 *
 * 判据是**时间轴**, ⛔ 不是「这个字段现在有没有值」:
 *   审批**前 / 当下**就存在、且影响批不批 ⇒ 留;
 *   审批**之后**才产生的履约 / 执行进度   ⇒ 从这一页去掉。
 *
 * 🔴 一个前提事实决定了「去掉」的做法: 这一页**采购也在用**。
 * 路由只有一条 `procurement/orders/:id` (router/index.ts), 而跳进来的入口包括
 * workflow/my-created (我发起的)、procurement/requisitions/detail、sales/orders/detail。
 * 所以采购动作 ⛔ 不许直接删, 只能**按角色区分** —— 载体是 `canWrite('procurement')`:
 *   procurement_manager `procurement: 'rw'` ⇒ true;
 *   finance_manager / cashier `procurement: 'r'` ⇒ false。
 */
describe('第二轮: 审批后才产生的内容不出现在这一页 (owner 2026-08-20)', () => {
  /**
   * 头部区域 —— 按钮的角色门禁都在这里。
   *
   * ⚠️ 收尾**不能**用「card-header 之后的第一个 `</template>`」: 那一个是
   * `<template v-if="canWrite">` 自己的闭合, 切出来的 header 里就没有它,
   * canWriteBlock() 于是永远切不到 (第一版实测就是这么红的)。
   * 改用「正文的第一张 el-descriptions」当边界 —— 头部按钮全在它之前。
   */
  function headerBlock(): string {
    const template = templateBlock();
    const start = template.indexOf('<div class="card-header">');
    const end = template.indexOf('<el-descriptions', start);
    if (start < 0 || end <= start) {
      throw new Error('切不到 card-header 块 —— 本组断言全部无意义');
    }
    return template.slice(start, end);
  }

  /** `<template v-if="canWrite">` 包住的那一段。 */
  function canWriteBlock(): string {
    const header = headerBlock();
    const start = header.indexOf('<template v-if="canWrite">');
    const end = header.indexOf('</template>', start);
    if (start < 0 || end <= start) {
      throw new Error('切不到 <template v-if="canWrite"> 块 —— 本组断言全部无意义');
    }
    return header.slice(start, end);
  }

  it('前提: 两个切片都切得到东西', () => {
    expect(headerBlock()).toContain('el-button');
    expect(headerBlock().length).toBeGreaterThan(200);
    expect(canWriteBlock()).toContain('el-button');
  });

  it('采购动作按角色区分, ⛔ 不是从代码里删掉 (这一页采购也在用)', () => {
    // 阳性对照: 三个采购动作必须**还在**。整体删除会让下面的门禁断言变成恒真式,
    // 也会把采购自己的入口一起砍掉 —— 那是这一条要防的事故。
    const canWriteOnly = canWriteBlock();
    for (const action of ['编辑草稿', '提交审批', '取消']) {
      expect(templateBlock()).toContain(action);
      expect(canWriteOnly).toContain(action);
    }

    // 单据追溯 / 对外供货单原来裸露在门禁之外, 现在各自挂上 canWrite。
    expect(headerBlock()).toContain('v-if="order && canWrite" plain @click="documentTraceVisible = true">单据追溯');
    expect(headerBlock()).toContain('v-if="canWrite && canDownloadSupplyOrder(order.status)"');

    // 财务自己那个只读入口不许被顺手收进 canWrite —— 财务正是要用它。
    // ⚠️ 切片取「它自己那一个 el-button 标签」, ⛔ 不用「往前数 N 个字符」——
    // 后者会随邻居按钮的长度变化而咬到别的行。
    const header = headerBlock();
    const oaLabelAt = header.indexOf('查看审批进度');
    expect(oaLabelAt).toBeGreaterThan(-1);
    const oaEntry = header.slice(header.lastIndexOf('<el-button', oaLabelAt), oaLabelAt);
    expect(oaEntry).toContain('v-if="order.status !== \'DRAFT\'"');
    expect(oaEntry).not.toContain('canWrite');
  });

  it('与付款判断关系弱的三格去掉: 采购类型 / 期望交货 / 开票提醒', () => {
    const template = templateBlock();
    for (const label of ['采购类型', '期望交货', '开票提醒']) {
      expect(template).not.toContain(`label="${label}"`);
    }
    // 绑定也一起消失, 否则只是换了个标签名继续渲染
    expect(template).not.toContain('order.purchaseType');
    expect(template).not.toContain('order.expectedDeliveryDate');
    expect(template).not.toContain('order.invoiceReminderDays');
    // 阳性对照: 同一张 descriptions 里审批**当下**就需要的格子必须还在,
    // 否则上面 6 条会因为「整块 descriptions 没了」而假绿。
    for (const label of ['订单编号', '下单日期', '合同号', '结算方式', '备注']) {
      expect(template).toContain(`label="${label}"`);
    }
  });

  it('OA 过程明细去掉三格, 保留「查看审批进度 / 前往 OA」入口', () => {
    const template = templateBlock();
    // 走到哪个节点、谁在处理、停了多久 —— 都是提交审批**之后**才产生的过程数据,
    // 而且头上已经有去 OA 的入口。
    for (const label of ['当前审批节点', '待处理角色/人员', '节点停留']) {
      expect(template).not.toContain(`label="${label}"`);
    }
    expect(template).not.toContain('approvalProgress.currentNodeNames');
    expect(template).not.toContain('approvalProgress.assignees');
    expect(template).not.toContain('approvalProgress.approverRoles');
    expect(template).not.toContain('approvalProgress.stayMinutes');

    // 阳性对照 ①: 入口还在 (否则财务连去 OA 的路都没了)。
    expect(template).toContain('查看审批进度 / 前往 OA');
    // 阳性对照 ②: 审批当下的结论与起点保留 —— 它们不是「审批之后才产生」的。
    expect(template).toContain('label="OA 状态"');
    expect(template).toContain('label="提交时间"');

    // 🔴 接口响应形状**故意保留**在 ApprovalProgress 接口上: 删掉会让下一个人
    // 以为后端不下发这些字段。断言的是「模板不渲染」, ⛔ 不是「类型里不存在」。
    expect(source).toContain('currentNodeNames?: string[]');
    expect(source).toContain('stayMinutes?: number');
  });

  it('付款状态先隐藏, 并登记「等后端补字段后恢复」', () => {
    // 后端实体上没有 paymentStatus ⇒ 这一格恒为「未归集」, 是个恒定的假读数。
    expect(templateBlock()).not.toContain('order.paymentStatus');
    expect(templateBlock()).not.toContain('label="付款状态"');

    // 登记必须留在源码里 (剥注释前的 rawSource), 且写明**谁需要它**和**什么条件下恢复**。
    // ⚠️ 尺子 ⛔ 不能直接 grep 「这个字段不需要」这句话: detail.vue 的注释里正是用
    // 这句话在**禁止**它, 闸会把自己的禁令数成违例 (第一版实测红在这一行 —— 本仓
    // 「闸咬到自己的注释」同形已多次, 正解是改尺子)。改成量「登记说清了没有」。
    expect(rawSource).toContain('付款状态');
    expect(rawSource).toContain('ArApTransaction.paymentStatus');
    expect(rawSource).toMatch(/财务\*\*需要\*\*它/);
    expect(rawSource).toContain('恢复条件');

    // 阳性对照: owner 明确豁免的四个财务格子一个都不许被顺手带走。
    for (const label of ['应付金额', '已付金额', '未付金额', '付款到期日', '收货状态', '发票状态']) {
      expect(templateBlock()).toContain(`label="${label}"`);
    }
  });

  it('附件收成只读: 内容留, 上传/删除入口按角色收', () => {
    const template = templateBlock();
    // 阳性对照: 附件列表必须还在 —— 合同 / 报价本身就是审批依据。
    expect(template).toContain('<AttachmentList');
    expect(template).toContain('entity-type="PURCHASE_ORDER"');
    // 删除按钮由列表组件的 readonly 收掉
    expect(template).toContain(':readonly="!canWrite"');
    // 拖拽上传区整块挂 canWrite (⛔ 不是删掉 —— 采购要用)
    expect(template).toContain('<AttachmentDropZone');
    const dropZoneAt = template.indexOf('<AttachmentDropZone');
    const dropZoneWrapper = template.slice(template.lastIndexOf('<div', dropZoneAt), dropZoneAt);
    expect(dropZoneWrapper).toContain('v-if="canWrite"');
  });

  it('AttachmentList 的 readonly 是可选的, 其余调用点行为不变', () => {
    const listSource = readFileSync(
      resolve(__dirname, '..', '..', '..', '..', 'components', 'attachment', 'AttachmentList.vue'),
      'utf8',
    );
    // 可选 prop ⇒ 缺省 undefined ⇒ falsy ⇒ 没传的地方删除按钮照旧显示。
    expect(listSource).toContain('readonly?: boolean');
    expect(listSource).toContain('v-if="!readonly"');
    // 阴性对照: ⛔ 不许写成默认只读, 那会静默改掉另外两个调用点。
    expect(listSource).not.toContain('readonly: boolean');
    expect(listSource).not.toMatch(/readonly:\s*true/);
  });
});
