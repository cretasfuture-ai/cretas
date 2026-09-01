import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 2026-08-25 生产实测: 走通库存盘点数量审批时发现 ——
 * 厂长在 OA 待办里看到的是
 *
 *     业务类型  库存调整审批
 *     业务单号  INVENTORY_ADJUSTMENT 5a5f4e93-a8ac-461d-8e03-53f14952edae
 *     往来单位  —      金额  —
 *
 * 点「详情」得到「「库存调整审批」暂未接入单据详情页」, 而「通过」前的确认框标题
 * 写着「**通过前请确认单据信息**」, 展示的却还是那串 UUID。
 *
 * ⇒ 系统在要求审批人**在完全看不到批了什么的情况下**批准一次真实的库存数量调整。
 *   这多半也是这条链路上线后一次都没被走通过的原因之一。
 *
 * ## 2026-09-01 扩面: 缺陷 30 是同一条判据的 PURCHASE_ORDER 实例
 *
 * LIUSHANMEN 生产实测: `lsm009`(财务主管)审完 `PO-20260901-0003` 点开这张单,
 * **整页跳 /403**。上面那条是「接了跳转但目标页读不到内容」, 这条是
 * 「接了跳转但**这个角色根本进不去**」—— 症状不同, 违反的是同一句话:
 * **审批人必须看得见自己在审什么。**
 *
 * ⛔ 修法**不是**把 `/procurement/orders/:id` 开给财务 —— 那会撤销 owner 拍过的板
 * (`V20261030_14__finance_manager_narrow.sql`:「又不能让他直接跨模块去看」),
 * 并且因为路径闸是前缀匹配、而「新建采购订单」是列表页里的对话框,
 * 会把新建面一起给财务。⇒ 够不着就退到**只读速览抽屉**(不注册路由)。
 */
const PENDING = readFileSync(resolve(__dirname, '../pending.vue'), 'utf8');
const ACTED = readFileSync(resolve(__dirname, '../acted.vue'), 'utf8');
const REACH = readFileSync(resolve(__dirname, '../approvalDocReach.ts'), 'utf8');
const STOCKTAKES = readFileSync(resolve(__dirname, '../../warehouse/stocktakes/index.vue'), 'utf8');

describe('库存调整审批必须让审批人看得见自己在批什么', () => {
  it('「详情」接进了盘点单据, 不再回落到「暂未接入单据详情页」', () => {
    expect(PENDING).toContain('INVENTORY_ADJUSTMENT: `/warehouse/stocktakes');
  });

  it('阳性对照: 闸读到的确实是那几份源码, 既有模块仍在', () => {
    // 读错文件/读到空串时, 上面那条会因「什么都不包含」而红得莫名其妙;
    // 这条先炸, 指出是仪器问题而不是产品问题。
    //
    // ⚠️ 下面两行是**对照**不是需求: 它们只说明「路由表还在这份文件里」。
    //    「审批人够不够得着」由本文件后半段那组断言承担 —— ⛔ 不要把这两行
    //    读成「必须用 router.push 跳过去」, 缺陷 30 的修法恰恰是够不着时不跳。
    expect(PENDING).toContain('PURCHASE_ORDER: `/procurement/orders/');
    expect(PENDING).toContain('SALES_ORDER: `/sales/orders/');
    expect(PENDING.length).toBeGreaterThan(3000);
    expect(ACTED.length).toBeGreaterThan(1000);
    expect(REACH.length).toBeGreaterThan(1000);
  });

  it('深链带的单号在盘点页真的被消费, ⛔ 不是一个被忽略的 query 参数', () => {
    expect(STOCKTAKES).toContain('route.query.stocktakeId');
    expect(STOCKTAKES).toContain('openDetail(target)');
    // 找不到那一单时要说清楚, 不许静默什么都不做
    expect(STOCKTAKES).toContain('未在当前列表中找到该盘点单');
  });

  // ── 缺陷 30 (2026-09-01): 审批人够不着目标路由时, 必须退到只读速览 ──

  it.each([
    ['pending.vue（待我审）', () => PENDING],
    ['acted.vue（我已办）', () => ACTED],
  ])('%s 不再无条件 router.push, 而是先判「点下去会不会 403」', (_label, read) => {
    const src = read();
    // ⚠️ 断言的是**调用点**不是 `openApprovalDoc(` —— 后者会被同文件里的**函数定义**
    //    满足。建闸时实测: 把调用改回 `router.push(target)` 之后, 只写
    //    `toContain('openApprovalDoc(')` 的版本**照样绿**(定义还在)。
    //    与本仓「grep 把 docstring 里提到函数名的那行也数成调用点」是同一族。
    expect(src, '没有把 target 交给统一入口 —— 够不着的角色会照旧被送去 /403')
      .toContain('openApprovalDoc(target,');
    expect(src, '没有接上只读速览抽屉 —— 够不着时只能报错, 审批人仍看不见自己在审什么')
      .toContain('OrderPeekDrawer');
    // ⛔ 阴性对照: 旧形状(拿到 target 就直接 push)不许回来。
    expect(
      src.replace(/\s+/g, ' '),
      '又回到了「拿到 target 就直接 router.push」—— 那正是缺陷 30 的形状',
    ).not.toContain('const target = routeByModule[row.moduleCode]; if (target) { router.push(target);');
  });

  it('可达性判定与守卫同源: 两道闸都查, 且模块从真实路由表 resolve, ⛔ 不另抄一份映射', () => {
    // 只查模块闸会给出偏乐观的答案: finance_manager 的路径白名单里有
    // /warehouse/stocktakes 却没有 /warehouse/materials —— 模块通、路径闸照样 403。
    expect(REACH, '漏了路径白名单那道闸').toContain('isPathAllowedForRole');
    expect(REACH, '没有从真实路由表 resolve 模块 —— 另抄一份映射就会与守卫漂开')
      .toContain('router.resolve(');
    expect(REACH).toContain('modulesAny');
    // 深链带 query 时路径闸只认路径, 不剥会把 /warehouse/stocktakes?x 判成不在白名单。
    expect(REACH, "没有剥掉 query, 带 ? 的深链会被误判成够不着").toContain("split('?')");
  });

  it('只有登记过抽屉的单据类型才退抽屉, ⛔ 其余不许假装能看', () => {
    expect(REACH).toContain('PEEKABLE_BY_MODULE');
    // 抽屉当前只承载采购/销售订单(它自己的 kind 就这两个值)。
    expect(REACH).toContain("PURCHASE_ORDER: 'purchase'");
    expect(REACH).toContain("SALES_ORDER: 'sales'");
    // 盘点没有抽屉 —— 它走的是本文件开头那条深链, ⛔ 不该被登记进来。
    expect(REACH, 'INVENTORY_ADJUSTMENT 被登记成可抽屉了, 但抽屉不承载盘点单')
      .not.toContain('INVENTORY_ADJUSTMENT:');
  });

  it('抽屉仍然不许注册成路由 (owner 定的形状: 读得到 ≠ 进得去菜单)', () => {
    // 与 orderPeekDrawer.readonly.spec.ts 同一条纪律, 在这里再钉一次 ——
    // 因为本次改动新增了两个引用点, 最容易的走偏就是「顺手给它加条路由」。
    const router = readFileSync(resolve(__dirname, '../../../router/index.ts'), 'utf8');
    expect(router, 'OrderPeekDrawer 被注册进了路由表 —— 那就等于跨模块导航又回来了')
      .not.toContain('OrderPeekDrawer');
  });

  it('确认框仍复用同一份单号/金额口径, ⛔ 不许为审批框另拼一套', () => {
    // 与 pending.vue 里原有的纪律一致: businessCodeOf / amountOf 只有一份。
    expect(PENDING).toContain("['业务单号', businessCodeOf(row)]");
    expect(PENDING).toContain("['金额', formatAmount(amountOf(row))]");
  });
});
