import { describe, expect, it } from 'vitest';
import { isHumanReadableName } from '../nodeLabel';

/**
 * 缺陷 23 第二处：审批链配置列表每行副标题把工作流的内部名直接摆出来
 * （生产实测 `PURCHASE_ORDER_APPROVAL - LIUSHANMEN admin approval`），
 * 而同一行左边的 `chineseName` 已经写着「采购订单审批」。
 *
 * 判据与 OA 那一处**共用这一个函数** —— ⛔ 不许两边各写一份「像不像英文 key」。
 * （本仓形态 D：同一个东西有两份，它一定会漂。）
 */
describe('isHumanReadableName — 「这是人看的中文名，还是内部配置 key？」', () => {
  it('生产上实际撞到的那两个串：判为内部 key', () => {
    expect(isHumanReadableName('LIUSHANMEN admin approval')).toBe(false);
    expect(isHumanReadableName('PURCHASE_ORDER_APPROVAL - LIUSHANMEN admin approval')).toBe(false);
  });

  it('种子里那些结构节点名也是英文：同样判为内部 key', () => {
    // 审批链画布里的 Submit / Approved / Rejected 属种子数据问题，对一线同样看不懂。
    expect(isHumanReadableName('Submit')).toBe(false);
    expect(isHumanReadableName('Approved')).toBe(false);
    expect(isHumanReadableName('Rejected')).toBe(false);
  });

  it('中文业务名判为可读 —— 管理员自定义的名字不能被误伤', () => {
    expect(isHumanReadableName('财务审批')).toBe(true);
    expect(isHumanReadableName('采购订单默认审批 (¥30000 阈值)')).toBe(true);
    // 中英混排只要含中文就算可读：管理员写「采购审批-EastChina」是给人看的。
    expect(isHumanReadableName('采购审批-EastChina')).toBe(true);
  });

  it('空值/空白判为不可读（调用方据此隐藏，而不是显示一个空标签）', () => {
    expect(isHumanReadableName('')).toBe(false);
    expect(isHumanReadableName('   ')).toBe(false);
    expect(isHumanReadableName(null)).toBe(false);
    expect(isHumanReadableName(undefined)).toBe(false);
  });

  it('⚠️ 已知的代理判据边界：纯英文但确实是人起的名字会被误判', () => {
    // 这不是缺陷，是**登记在案的取舍**：静态判不出「这串会不会被一线看懂」，
    // 用「含不含中文」当代理。误判的后果只是少显示一个冗余副标题（仍在 tooltip 里），
    // 而漏判的后果是把内部 key 甩给一线。⇒ 这条断言记录现状，改判据时会红。
    expect(isHumanReadableName('Fast Track')).toBe(false);
  });
});
