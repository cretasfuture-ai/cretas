import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 审查发现 2: 新建业务审批(showNoApproval, 该 decisionType 还没有任何已保存定义)时,
 * createDefaultDraft 曾经只填画布 nodes/edges(一个虚构的"主管审批"默认节点),
 * 从不碰 stepFlow —— `useStepList = lockDecisionType && stepFlow.value !== null`
 * 判定为假, **静默退回画布**, 把整个 PR 要消灭的东西摆在第一次使用的用户面前,
 * 且没有任何 fallback 提示(stepModeFallbackReason 同样没被设置)。
 *
 * ⚠️ 这不是一个会抛异常的"硬崩溃"——在 jsdom/vitest 下逐层追踪过 useStepList /
 * stepFlow / canSave / serializeStepFlow / StepListEditor 的每一处消费点, 没有一处
 * 无守卫地解引用 null 从而抛出。真正可观测的坏行为是**行为性的**: 业务入口新建
 * 时悄悄用了画布(平台管理员才该用的高级模式), 且 canSave 恒为 disabled(见下面
 * "canSave 必须看 stepFlow" 用例)——对最终用户而言, "打不开该有的编辑器"和
 * "保存按钮永远点不动"与一次崩溃一样, 都是这条路完全走不通。
 *
 * 修法: 新建直接给一个**空步骤列表**, ⛔ 不是换一个不同形状的虚构默认值。
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

async function mountWithNoExistingDefinition() {
  api.getDecisionTypes.mockResolvedValue({ success: true, data: ['SALES_ORDER_APPROVAL'] });
  api.getDecisionTypesMetadata.mockResolvedValue({ success: true, data: [] });
  // 该业务类型还没有任何已保存的定义 —— 这正是"新建/no saved definition yet"。
  api.getWorkflowsByDecisionType.mockResolvedValue({ success: true, data: [] });
  api.getWorkflowById.mockResolvedValue({ success: false });
  api.getApprovalDirectory.mockResolvedValue({ roles: [], users: [] });
  api.getRunningInstanceCount.mockResolvedValue({ success: true, data: 0 });
  api.createWorkflow.mockResolvedValue({
    success: true,
    data: { id: 'wf-new', decisionType: 'SALES_ORDER_APPROVAL', name: 'x', publishStatus: 'draft', version: 1 },
  });

  const wrapper = shallowMount(ApprovalWorkflowEditor, {
    props: { embedded: true, initialDecisionType: 'SALES_ORDER_APPROVAL', lockDecisionType: true },
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

describe('新建业务审批(无既有定义)必须直接进入步骤列表, ⛔ 不静默退回画布', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('showNoApproval 为真 —— 复现前置条件: 该业务类型确实还没有任何已保存定义', async () => {
    const wrapper = await mountWithNoExistingDefinition();
    expect((wrapper.vm as any).showNoApproval).toBe(true);
    expect(wrapper.text()).toContain('当前不需要审批');
  });

  it('🔴 点"启用该业务审批"之后必须进入步骤列表模式(而不是静默退回画布)', async () => {
    const wrapper = await mountWithNoExistingDefinition();

    await (wrapper.vm as any).enableApprovalDraft();
    await flushPromises();

    expect(
      (wrapper.vm as any).useStepList,
      'useStepList 是假 —— 业务入口新建时又退回了画布, 整个 PR 要消灭的东西回来了',
    ).toBe(true);
    expect(
      (wrapper.vm as any).stepFlow,
      'stepFlow 不是数组 —— 新建之后仍停在初始值 null',
    ).toEqual([]);

    // 不产出虚构默认值: 画布节点必须是空的, ⛔ 不该再看到"主管审批"这个占位节点。
    const nodes = (wrapper.vm as any).nodes as Array<{ data?: { label?: string } }>;
    expect(nodes, '不该再产出画布节点 —— 步骤列表模式下这份状态应该保持空').toHaveLength(0);

    // 没有任何 fallback 横幅 —— 这不是"读不出来退回画布", 是"本来就是新建"。
    const alert = wrapper.find('.step-fallback-alert');
    expect(alert.exists(), '新建不是 fallback 场景, 不该出现退回横幅').toBe(false);
  });

  it('canSave 必须看 stepFlow, ⛔ 只看 nodes.length —— 否则新建的步骤列表永远保存不了', async () => {
    const wrapper = await mountWithNoExistingDefinition();
    await (wrapper.vm as any).enableApprovalDraft();
    await flushPromises();

    // 空步骤列表: 还没有可保存的内容。
    expect((wrapper.vm as any).canSave).toBe(false);

    // 用户通过 StepListEditor 加了一级审批(等价于点"+加一级审批") —— 走的是唯一写入口。
    (wrapper.vm as any).onStepsUpdate([
      { id: 's1', type: 'approval', label: '第 1 级审批', approver: { roles: ['finance_manager'], userIds: [] }, mode: 'or' },
    ]);
    await flushPromises();

    expect(
      (wrapper.vm as any).canSave,
      'canSave 只判 nodes.value.length —— 步骤列表模式下这份状态恒为空, 保存按钮永远 disabled',
    ).toBe(true);
  });

  it('加一级审批后保存: 走 stepsToGraph 生成图, 不是画布那份虚构默认值', async () => {
    const wrapper = await mountWithNoExistingDefinition();
    await (wrapper.vm as any).enableApprovalDraft();
    (wrapper.vm as any).onStepsUpdate([
      { id: 's1', type: 'approval', label: '财务审批', approver: { roles: ['finance_manager'], userIds: [] }, mode: 'or' },
    ]);
    await flushPromises();

    await (wrapper.vm as any).handleSave();
    await flushPromises();

    expect(api.createWorkflow).toHaveBeenCalledTimes(1);
    const payload = api.createWorkflow.mock.calls[0][1];
    const approvalNode = payload.nodes.find((n: { type: string }) => n.type === 'approval');
    expect(approvalNode?.label, '保存的图里没有用户刚加的那一级审批').toBe('财务审批');
    expect(
      payload.nodes.some((n: { label?: string }) => n.label === '主管审批'),
      '出现了旧的虚构默认节点"主管审批" —— fix 被撤销了',
    ).toBe(false);
  });
});
