/**
 * 「批次」这个概念**不许出现在 App 的发货流程里**（2026-08-20 owner 拍板）。
 *
 * 🔴 这个文件的断言方向**翻过面**，值得记一笔：
 *
 * 同一天早些时候，它守的还是「App 上要能分配批次」——因为仓管被
 * 「还有 N 行未分配批次，请到网页端分配」挡死在手机上。我给 App 接了一键 FIFO 分配。
 *
 * 然后 owner 看了实现说：**太复杂了**。原话是
 * 「批次他们自己都管不过来，我还得帮他录入」「追溯按订单号去追溯就行，按批次没必要」。
 *
 * ⇒ 需求变了，不是实现错了。断言跟着需求走（形态 C‴：断言守着一个现在判定为多余的行为，
 *   它红得理直气壮，最容易被误读成「我改错了」然后把改动回退掉）。
 * ⛔ 但改断言不能滑成删断言：翻成阴性对照，钉住这个概念不许悄悄回来。
 *
 * ▎自动分配**不丢数据**：HACCP 放行闸与成本核算读的都是「已分配的批次」，
 * ▎不关心是人点的还是系统算的。放弃的只有「实际搬走的那箱 ≠ FIFO 推荐时能人工纠正」。
 */
import fs from 'fs';
import path from 'path';

const sourceRoot = path.resolve(__dirname, '../../../');
const read = (rel: string) => fs.readFileSync(path.resolve(sourceRoot, rel), 'utf8');

/** ⛔ 剥注释 —— 否则闸会把自己的说明当被测对象（本仓当天栽过四次）。 */
const strip = (src: string) =>
  src
    .replace(/\{\/\*[\s\S]*?\*\/\}/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

const confirm = strip(read('screens/warehouse/outbound/WHDeliveryConfirmScreen.tsx'));
const backendRoot = path.resolve(sourceRoot, '../../../backend/java/cretas-api/src/main/java/com/cretas/aims');
/*
 * ⚠️ Java 源码也要剥注释再断言。
 * 🔴 实测教训：第一版直接扫原文，于是「把那行注释掉」这种变异**抓不住** ——
 *    因为被注释掉的代码里那个字符串还在，`toContain` 照样为真。
 *    (本仓 C″：变异不红要先分清「断言没用」还是「变异没打进去」。这次是前者。)
 */
const stripJava = (src: string) =>
  src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');

const salesService = stripJava(fs.readFileSync(
  path.resolve(backendRoot, 'service/inventory/impl/SalesServiceImpl.java'), 'utf8'));
const allocService = stripJava(fs.readFileSync(
  path.resolve(backendRoot, 'service/sales/impl/SalesDeliveryBatchAllocationServiceImpl.java'), 'utf8'));

describe('发货确认 · App 上不出现「批次」概念', () => {
  it('前提：确实读到了发货确认屏（否则下面的阴性断言全是恒真）', () => {
    expect(confirm).toContain('确认并扣库存');
    expect(confirm.length).toBeGreaterThan(2000);
  });

  it('⛔ 界面上不再出现「分配批次」这个说法', () => {
    expect(confirm).not.toContain('分配批次');
    expect(confirm).not.toContain('未分配');
  });

  it('⛔ 不再有分配按钮, 也不再从 App 写分配', () => {
    expect(confirm).not.toContain('wh-confirm-auto-allocate');
    /*
     * ⚠️ 这条断言收窄过一次: 原来是「不许出现 salesDeliveryBatchAllocationApiClient」,
     * 但后来加了**只读**的取货批次提示(recommendFifo), 那个 client 又出现了 ——
     * 断言当场变红, 而行为并没有退化。
     * ⇒ 要守的从来不是「碰不碰这个 client」, 是「App 不写分配」。
     */
    expect(confirm).not.toMatch(/salesDeliveryBatchAllocationApiClient\.allocate\(/);
    // 只读的推荐是允许的
    expect(confirm).toMatch(/salesDeliveryBatchAllocationApiClient\.recommendFifo\(/);
  });

  it('⛔ 待确认单能不能提交只取决于数量；历史单永远只读', () => {
    expect(confirm).toMatch(/canSubmit\s*=\s*!readOnlyHistory && !loading && !submitting && canSubmitRows\(items\);/);
    expect(confirm).toContain("!isConfirmableDeliveryStatus(record.status)");
  });
});

describe('发货确认 · 告诉仓管该拿哪一批（信息，不是决定）', () => {
  /*
   * 🔴 2026-08-20 实测: 一单账实分家 —— 系统按先进先出扣了 08-19 的老批次,
   * 而仓管拿的是当天刚生产那 4 盒。**系统做的是对的**(食品先进先出防过期),
   * 落差在于没人告诉他该拿哪一批。
   *
   * ⇒ 根治不是改算法(样本只有 3 条分配记录, 且那单是测试, 不足以推断「现产现发是常态」),
   *   而是把系统的决定显示出来。零点击, 不牺牲准确度。
   */
  it('每行显示取货批次', () => {
    expect(confirm).toContain('pickBatches');
    expect(confirm).toContain('wh-pick-batches-');
    expect(confirm).toContain('请从这些货里取');
  });

  it('批次号带生产日期（他要靠这个在货架上认出来）', () => {
    expect(confirm).toMatch(/productionDate[\s\S]{0,120}生产/);
  });

  it('⛔ 仍然只是信息 —— 不给选择、不加操作', () => {
    // 没有任何让他挑批次的控件
    expect(confirm).not.toContain('RadioButton');
    expect(confirm).not.toContain('wh-confirm-auto-allocate');
  });

  it('🔴 取货提示拿不到不许阻断发货（它是锦上添花, 不是前置条件）', () => {
    /*
     * 🔴 这条第一版写成「catch 块里要有『锦上添花』这句注释」——
     * 而本文件**剥了注释再断言**, 那句话根本不在被测字符串里, 必然红。
     * ⚠️ 又一次「闸在量注释」。⇒ 改成量**行为**: 那个 catch 里不许有任何报错动作。
     */
    const pickFetch = confirm.match(/recommendFifo\([\s\S]*?catch\s*\{[\s\S]*?\}/)?.[0] ?? '';
    if (pickFetch === '') {
      throw new Error('没抓到取货批次那段, 下面的阴性断言会恒真');
    }
    const tail = pickFetch.slice(pickFetch.lastIndexOf('catch'));
    expect(tail).not.toContain('handleError');
    expect(tail).not.toContain('setSnackbar');
  });
});

describe('后端 · 确认发货时自动按先进先出分配', () => {
  it('存在自动分配实现', () => {
    expect(allocService).toContain('autoAllocateFifo');
    expect(allocService).toContain('recommendFifo');
  });

  it('🔴 库存不够时不做部分分配（分配一半再放行会让下游拿到对不上的数）', () => {
    expect(allocService).toMatch(/remaining\.signum\(\) > 0 \|\| picks\.isEmpty\(\)[\s\S]{0,200}return false;/);
  });

  it('发货确认先自动分配，⛔ 不再直接抛「未完成批次分配」', () => {
    expect(salesService).toContain('autoAllocateFifo');
    expect(salesService).not.toContain('未完成批次分配，无法确认发货');
  });

  it('🔴 库存真不够时仍然拦，且话要说得仓管做得到', () => {
    expect(salesService).toContain('可用库存不足，无法发货');
    // ⛔ 不能再指向一个手机上没有的按钮
    expect(salesService).not.toContain('点击「分配批次」按钮');
  });

  it('🔴 HACCP 放行闸仍在（自动分配不该把食品安全一起省掉）', () => {
    /*
     * 🔴 实测教训：第一版写的是 `toContain('assertHaccpReleaseAllowed')`，
     * 而这个名字**在方法定义处也出现** —— 于是把调用整行删掉，断言照样为真。
     * 本仓 C⁸：字符串计数量的是**文本**，我要问的是「它**被调用**了吗」。
     * ⇒ 钉调用点（带实参），⛔ 不钉名字。
     */
    expect(salesService).toMatch(/^\s*assertHaccpReleaseAllowed\(factoryId, record\);/m);
    // 它读的是「已分配的批次」——自动分配照样喂得到它
    expect(salesService).toMatch(/assertHaccpReleaseAllowed[\s\S]{0,4000}listByDeliveryItem/);
  });
});
