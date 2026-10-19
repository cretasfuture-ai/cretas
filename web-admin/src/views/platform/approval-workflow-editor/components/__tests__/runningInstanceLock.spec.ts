import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 有在途审批实例时，已发布版本必须锁定，且 ⛔ 不提供任何「仍然直接改」的解锁入口。
 *
 * <h3>守的是什么</h3>
 * 步骤列表模式保存走 `stepsToGraph` **确定性重建**图，节点 id 会被重命名
 * （`start_1 -> start`、`end_approved -> approved`，2026-09-01 测试环境实测）。
 * 而 `ApprovalWorkflowInstance.currentNodeIds` 存的就是节点 id 字符串，引擎按 id
 * 找当前节点 ⇒ 有在途实例时保存 = 那些单据当场搁浅（缺陷 27 同形）。
 *
 * <h3>⚠️ 为什么「看到 disabled」本身证明不了任何事</h3>
 * `readOnlyWorkflow = 已发布 && 在途数 !== 0` —— **`null`（计数没查回来）同样锁**。
 * 所以「它是只读的」这个读数，在「计数请求失败」时**一模一样**。
 * 2026-09-01 验收时我就先拿到过这样一个假绿：桩只保证请求被拦截、没保证响应被解析，
 * 于是验到的是「未知⇒安全锁住」，却以为验到了「有在途⇒锁住」——**两条判据不同**。
 *
 * ⇒ 本文件每个「锁住」的断言都**配一条证明计数真的到达组件**的断言
 * （界面上出现「有 N 条在途审批」中的那个 N）。⛔ 只断言 disabled 的用例不算数。
 */

const api = vi.hoisted(() => ({
  getDecisionTypes: vi.fn(),
  getDecisionTypesMetadata: vi.fn(),
  getWorkflowsByDecisionType: vi.fn(),
  getWorkflowById: vi.fn(),
  createWorkflow: vi.fn(),
  cloneWorkflowDraft: vi.fn(),
  updateWorkflow: vi.fn(),
  deleteWorkflow: vi.fn(),
  publishWorkflow: vi.fn(),
  archiveWorkflow: vi.fn(),
  validateWorkflow: vi.fn(),
  getApprovalDirectory: vi.fn(),
  getRunningInstanceCount: vi.fn(),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/api/approvalWorkflow', () => api);

import ApprovalWorkflowEditor from '../../index.vue';

const PUBLISHED_WORKFLOW = {
  id: 'wf-locked',
  factoryId: 'F006',
  decisionType: 'INVENTORY_TRANSFER_APPROVAL',
  name: '库存调拨审批（财务复核单级）',
  publishStatus: 'published',
  enabled: true,
  version: 1,
  startNodeId: 'start_1',
  nodesJson: JSON.stringify([
    { id: 'start_1', type: 'start', label: '提交', config: {}, position: { x: 0, y: 0 } },
    { id: 'approval_finance', type: 'approval', label: '财务经理复核', config: { approverRoles: ['finance_manager'] }, position: { x: 1, y: 0 } },
    { id: 'end_approved', type: 'end', label: '通过', config: { outcome: 'APPROVED' }, position: { x: 2, y: 0 } },
    { id: 'end_rejected', type: 'end', label: '驳回', config: { outcome: 'REJECTED' }, position: { x: 3, y: 0 } },
  ]),
  edgesJson: JSON.stringify([
    { id: 'e1', source: 'start_1', target: 'approval_finance', condition: null },
    { id: 'e2', source: 'approval_finance', target: 'end_approved', condition: "#decision == 'APPROVE'" },
    { id: 'e3', source: 'approval_finance', target: 'end_rejected', condition: "#decision == 'REJECT'" },
  ]),
};

async function mountWithRunningCount(count: number | null) {
  api.getRunningInstanceCount.mockResolvedValue(
    count === null ? { success: false } : { success: true, data: count },
  );
  const wrapper = shallowMount(ApprovalWorkflowEditor, {
    props: { embedded: true, initialDecisionType: 'INVENTORY_TRANSFER_APPROVAL', lockDecisionType: true },
    global: {
      stubs: {
        ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
      },
    },
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

describe('在途实例锁定：⛔ 没有「仍然直接改」这条路', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getDecisionTypes.mockResolvedValue({ success: true, data: ['INVENTORY_TRANSFER_APPROVAL'] });
    api.getDecisionTypesMetadata.mockResolvedValue({ success: true, data: [] });
    api.getWorkflowsByDecisionType.mockResolvedValue({ success: true, data: [PUBLISHED_WORKFLOW] });
    api.getWorkflowById.mockResolvedValue({ success: true, data: PUBLISHED_WORKFLOW });
    api.getApprovalDirectory.mockResolvedValue({ roles: [], users: [] });
  });

  it('🔴 有 3 条在途：锁定、给出真实后果、且没有任何解锁按钮', async () => {
    const wrapper = await mountWithRunningCount(3);
    const text = wrapper.text();

    // ① 先证明计数真的到达了组件 —— 否则下面「锁住」的读数不可判定（null 也会锁）。
    expect(text, '界面上没有出现在途条数 —— 无法区分「有在途」与「计数没查回来」').toContain('3');
    expect(text).toContain('在途审批');

    // ② 逃生门必须不存在
    expect(text, '「仍然直接改」还在 —— 有在途时保存会重命名节点 id, 把在途单据搁浅').not.toContain('仍然直接改');

    // ③ 留下的路必须还在
    expect(text, '锁住了却没给出路 —— 用户无法完成"改一份定义"这个真实目的').toContain('克隆为新版本');

    // ④ 文案要说真实后果, ⛔ 不能只说「影响判定依据」
    expect(text, '文案没说清后果 —— 「影响判定依据」把「单据再也推不动」说轻了').toContain('无法推进');
  });

  it('阳性对照：0 条在途时不锁 —— 证明这道锁不是恒真的', async () => {
    const wrapper = await mountWithRunningCount(0);
    const text = wrapper.text();

    expect(text, '0 条在途还显示在途锁定提示 —— 这道锁恒真, 上面那条断言就没有意义').not.toContain('在途审批，本版本已锁定');
    expect(text).not.toContain('仍然直接改');
  });

  it('零在途的已发布版本仍可克隆为独立草稿，且不覆盖原定义', async () => {
    const originalGraph = PUBLISHED_WORKFLOW.nodesJson;
    const draft = { ...PUBLISHED_WORKFLOW, id: 'wf-draft-v2', version: 2, publishStatus: 'draft', enabled: false };
    api.cloneWorkflowDraft.mockResolvedValue({ success: true, data: draft });
    api.getWorkflowById.mockImplementation((_factoryId: string, id: string) => Promise.resolve({
      success: true, data: id === draft.id ? draft : PUBLISHED_WORKFLOW,
    }));
    const wrapper = await mountWithRunningCount(0);
    expect(api.getRunningInstanceCount).toHaveBeenCalledWith('F006', PUBLISHED_WORKFLOW.id);
    expect(wrapper.text()).toContain('保存修改');
    const cloneButton = wrapper.findAll('button').find(button => button.text().trim() === '克隆为新版本');
    expect(cloneButton, '零在途时隐藏克隆会迫使用户覆盖原定义').toBeDefined();
    if (!cloneButton) throw new Error('Expected clone button');

    await cloneButton.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(api.cloneWorkflowDraft).toHaveBeenCalledExactlyOnceWith('F006', PUBLISHED_WORKFLOW.id);
    expect(api.getWorkflowById).toHaveBeenCalledWith('F006', draft.id);
    expect(api.updateWorkflow).not.toHaveBeenCalled();
    expect(PUBLISHED_WORKFLOW.nodesJson).toBe(originalGraph);
    expect(wrapper.text()).toContain('保存草稿');
    expect(wrapper.text()).not.toContain('克隆为新版本');
    wrapper.unmount();
  });

  it('零在途的历史归档版本同样保留克隆入口', async () => {
    const archived = { ...PUBLISHED_WORKFLOW, publishStatus: 'archived', enabled: false };
    api.getWorkflowsByDecisionType.mockResolvedValue({ success: true, data: [archived] });
    api.getWorkflowById.mockResolvedValue({ success: true, data: archived });
    const wrapper = await mountWithRunningCount(0);
    expect(api.getRunningInstanceCount).toHaveBeenCalledWith('F006', archived.id);
    expect(wrapper.text()).toContain('克隆为新版本');
    wrapper.unmount();
  });

  it('未发布草稿不提供再次克隆，仍显示保存草稿', async () => {
    const draft = { ...PUBLISHED_WORKFLOW, publishStatus: 'draft', enabled: false };
    api.getWorkflowsByDecisionType.mockResolvedValue({ success: true, data: [draft] });
    api.getWorkflowById.mockResolvedValue({ success: true, data: draft });
    const wrapper = await mountWithRunningCount(0);
    expect(wrapper.text()).toContain('保存草稿');
    expect(wrapper.text()).not.toContain('克隆为新版本');
    expect(api.cloneWorkflowDraft).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('计数查不回来（null）时按锁住处理 —— 安全默认，但 ⛔ 不谎报条数', async () => {
    const wrapper = await mountWithRunningCount(null);
    const text = wrapper.text();

    // 未知就是未知：不显示「有 N 条在途审批」，⛔ 不编一个数出来。
    expect(text, '计数未知却显示了在途条数 —— 那是编出来的').not.toContain('在途审批，本版本已锁定');
    expect(text, '任何情况下都不该再出现解锁入口').not.toContain('仍然直接改');
    expect(text).toContain('克隆为新版本');
  });
});

/**
 * `force` 是**后端**据以放行的那个标志：`ApprovalWorkflowServiceImpl.update` 对
 * published/archived 在 `!force` 时执行 `assertNoRunningInstances`。
 *
 * ⚠️ 上面那组用例只守住了「界面上没有解锁按钮」。实测（2026-09-01 变异对照）：
 * 把 `force` 改成 `true` 时，**上面 84 条全绿** —— 它们看不见这个标志。
 * ⇒ 必须单独钉住它，否则后端那道闸可以被前端一行改动绕过而无人察觉。
 */
describe('force 标志必须钉死为 false', () => {
  const SRC = 'src/views/platform/approval-workflow-editor/index.vue';

  it('🔴 updateWorkflow 只能带 force: false 字面量，⛔ 不许绑到任何变量', async () => {
    const { readFileSync } = await import('node:fs');
    const { resolve } = await import('node:path');
    const src = readFileSync(resolve(process.cwd(), SRC), 'utf8');

    const forceLines = src.split('\n').filter(l => /^\s*force:/.test(l));
    expect(forceLines.length, '出现了多于一处 force: —— 先数再改').toBe(1);
    expect(
      forceLines[0].trim(),
      'force 不再是 false 字面量 —— 后端 assertNoRunningInstances 会被跳过, '
        + '有在途实例时保存将重命名节点 id 并搁浅那些单据',
    ).toBe('force: false,');
  });

  it('⛔ 不存在任何把 force 解锁的状态标志', () => {
    const { readFileSync } = require('node:fs') as typeof import('node:fs');
    const { resolve } = require('node:path') as typeof import('node:path');
    const src = readFileSync(resolve(process.cwd(), SRC), 'utf8');
    const live = src.split('\n').filter(l => (
      l.includes('forceEditUnlocked') && !l.trim().startsWith('*') && !l.trim().startsWith('<!--') && !l.includes('🔴')
    ));
    expect(live, '「仍然直接改」的解锁状态又回来了').toEqual([]);
  });
});
