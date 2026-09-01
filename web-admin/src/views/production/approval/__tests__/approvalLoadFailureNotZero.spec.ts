/**
 * LIUSHANMEN 生产事故 (2026-09-01): lsm008 (六膳门质量经理, quality:* 权限) 打开
 * /production/approval, 后端 403, 页面渲染「待审批 0」+「暂无数据」。lsm015 (生产经理)
 * 几分钟后打开同一 URL: 200, 待审批 26。
 *
 * 机制: loadData() 的 catch 块吞掉 403, allData 停在初始值 [] 上, 模板据此渲染一个
 * confident 的 0 —— 「加载失败」和「真的没有待审批」在数据模型里是同一个状态。
 * response.success === false 那条分支同样静默不做事。loadCorrectableReports() 是
 * 同一形状的第二处 (硬约束 8 count: BEFORE=2, 两处都已修)。
 *
 * 本文件按硬约束 9 断言两件事, 缺一不可:
 *   1. 虚假的「待审批 0」不再出现
 *   2. 一个正向的错误信号确实出现了 (仅第 1 条不够 —— 组件整个渲染失败也会产生
 *      「没有 0」这个观察, 必须同时证明"失败被喊出来了")
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ApiError } from '@/types/api';

const getPendingApprovalsMock = vi.fn();
const getCorrectableReportsMock = vi.fn();

vi.mock('@/api/processProduction', () => ({
  getPendingApprovals: (...args: unknown[]) => getPendingApprovalsMock(...args),
  approveReport: vi.fn(),
  rejectReport: vi.fn(),
  batchApproveReports: vi.fn(),
  getCorrectableReports: (...args: unknown[]) => getCorrectableReportsMock(...args),
  submitReportCorrection: vi.fn(),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
// 注意: 故意不 mock '@/utils/errorToast' —— getErrorMessage/handleCatchError 用真实实现,
// 它们是本次修复直接依赖的行为, 桩一遍等于测试桩自己而不是测试组件是否接上了真实契约。
vi.mock('@/utils/processSheetUnits', () => ({ displayProcessUnit: (v: unknown) => String(v ?? '') }));
vi.mock('@/components/approval/OpinionInputDialog.vue', () => ({
  default: { name: 'OpinionInputDialog', render: () => null },
}));

import ApprovalList from '../list.vue';

// ⚠️ 不要给 'el-card'/'el-tag'/'el-alert' 传 stubs（哪怕显式设 false 也不行）——
// 实测 VTU 的 stub 解析对显式 false 的条目走"当真实组件解析"路径, 而非注册
// 组件时会得到一个空的 `{ name: 'el-card' }` stub（无 render/template）, 导致
// 整棵子树渲染为空。不列入 stubs 时 Vue 走正常的"未注册组件退化为原生标签"
// 路径, 子节点照常渲染 —— 这里需要断言真实文本内容, 所以必须走这条路径。
//
// 唯一必须 stub 的是 'el-table-column'：它内部用 `#default="{ row }"` 作用域插槽,
// 若也走"退化为原生标签"路径, Vue 会在没有 row 的情况下直接调用插槽函数解构崩溃
// (TypeError: Cannot destructure property 'row' of 'undefined')。stub 成 true 让
// VTU 生成一个不执行插槽内容的占位组件, 绕开这条与本次修复无关的崩溃路径。
function mountApproval() {
  return mount(ApprovalList, { global: { stubs: { 'el-table-column': true } } });
}

describe('报工审批页 — 加载失败不能长成「暂无待审批」(LIUSHANMEN 403 事故)', () => {
  beforeEach(() => {
    getPendingApprovalsMock.mockReset();
    getCorrectableReportsMock.mockReset().mockResolvedValue({ success: true, data: { content: [] } });
  });

  it('getPendingApprovals 403 时: 不渲染「待审批 0」, 且渲染一个正向的错误提示', async () => {
    getPendingApprovalsMock.mockRejectedValue(new ApiError('权限不足', 'FORBIDDEN', 403));

    const wrapper = mountApproval();
    await flushPromises();

    // 硬约束 9 断言 ① —— 虚假的 0 不再出现
    expect(wrapper.text()).not.toContain('待审批 0');
    // 硬约束 9 断言 ② —— 必须同时有一个「一定会出现」的正向信号, 否则「没有 0」
    // 也可能只是组件整个没渲染出来这一种更糟的情况
    expect(wrapper.find('.load-error').exists()).toBe(true);
    expect(wrapper.text()).toContain('待审批 —');
    expect(wrapper.text()).toContain('权限不足');
  });

  it('getPendingApprovals resolves { success:false } 时: 同样不是「待审批 0」', async () => {
    getPendingApprovalsMock.mockResolvedValue({ success: false, data: null, message: '工厂上下文缺失' });

    const wrapper = mountApproval();
    await flushPromises();

    expect(wrapper.text()).not.toContain('待审批 0');
    expect(wrapper.find('.load-error').exists()).toBe(true);
    expect(wrapper.text()).toContain('工厂上下文缺失');
  });

  it('getPendingApprovals 200 且确实无待审批时: 仍然是「待审批 0」, 不受本次修复影响', async () => {
    getPendingApprovalsMock.mockResolvedValue({ success: true, data: { content: [] } });

    const wrapper = mountApproval();
    await flushPromises();

    // 阳性对照: 真正的空队列必须仍然显示 0, 不能被本次修复误伤成一直显示 "—"
    expect(wrapper.text()).toContain('待审批 0');
    expect(wrapper.find('.load-error').exists()).toBe(false);
  });

  it('loadCorrectableReports (硬约束 8 第二处) 403 时: 报工核对表不冒充「暂无未结单报工」', async () => {
    getPendingApprovalsMock.mockResolvedValue({ success: true, data: { content: [] } });
    getCorrectableReportsMock.mockRejectedValue(new ApiError('权限不足', 'FORBIDDEN', 403));

    const wrapper = mountApproval();
    await flushPromises();

    // el-alert 在这个测试环境里未注册, 退化为原生标签 —— :title 这类 prop
    // 变成普通 DOM attribute, 不出现在 wrapper.text() 里(那只反映 slot 内容),
    // 所以标题文案走 attribute 断言, 而 span 内容(硬约束9 的正向信号)走 text 断言。
    const errorPanel = wrapper.find('.load-error');
    expect(errorPanel.exists()).toBe(true);
    expect(errorPanel.attributes('title')).toContain('报工核对列表加载失败');
    expect(wrapper.text()).toContain('权限不足'); // 硬约束9 ② 正向信号: 具体错误消息渲染出来了
    expect(wrapper.text()).not.toContain('暂无未结单报工');
  });
});
