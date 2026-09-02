import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { STEP_LIST_FALLBACK_TEXT } from '../../lib/stepGraph';

/**
 * 退回画布时的横幅文案必须由 {@link explainStepListFallback} **算出来**，
 * ⛔ 不许硬编码一句了事。
 *
 * <h3>为什么每个用例只断言"精确等于"，⛔ 不用"包含关键词"</h3>
 * "包含关键词"这种松判据抓不住「文案换了但仍然是硬编码的另一句假话」这类回归——
 * 只要新的硬编码字面量里凑巧含有关键词就会被松判据放过。这里直接从
 * `STEP_LIST_FALLBACK_TEXT`（stepGraph.ts 里判定逻辑的**唯一**权威文案表）
 * 取字符串来比对，横幅显示的必须和那张表逐字一致——这样"横幅是不是真的从
 * 判定结果算出来的"这件事，不依赖我在这个测试文件里手抄一份中文文案
 * （抄错/漂移都会被此闸的其它用例连带发现）。
 *
 * 源码层的静态闸见 `businessModeUsesStepList.source.spec.ts`
 * 「stepModeFallbackReason 必须来自 explainStepListFallback」。二者分工：
 * 那份闸看不见运行时；这份闸挂载真实组件，证明"喂不同的图，横幅真的换成
 * 对应的原因"，而不只是"源码里出现了函数名"。
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

function makeDto(nodesJson: string, edgesJson: string, startNodeId = 'start') {
  return {
    id: 'wf-1',
    factoryId: 'F006',
    decisionType: 'SALES_ORDER_APPROVAL',
    name: '测试审批流',
    publishStatus: 'draft',
    enabled: true,
    version: 1,
    startNodeId,
    nodesJson,
    edgesJson,
  };
}

/**
 * `el-alert` 未在测试环境注册（未 stub 成会渲染插槽内容的组件），Vue 把它当**未解析的
 * 自定义元素**处理：`:description="..."` 是**属性绑定**（不是插槽子节点文本），
 * 于是 `wrapper.text()` 读不到它——文本抓取只看渲染出来的文本节点。
 * ⇒ 必须直接读那个元素的 `description` 属性，⛔ 不能指望它出现在 `.text()` 里。
 */
function bannerDescription(wrapper: ReturnType<typeof shallowMount>): string | undefined {
  const alert = wrapper.find('.step-fallback-alert');
  return alert.exists() ? alert.attributes('description') : undefined;
}

async function mountWithDto(theDto: ReturnType<typeof makeDto>) {
  api.getDecisionTypes.mockResolvedValue({ success: true, data: ['SALES_ORDER_APPROVAL'] });
  api.getDecisionTypesMetadata.mockResolvedValue({ success: true, data: [] });
  api.getWorkflowsByDecisionType.mockResolvedValue({ success: true, data: [theDto] });
  api.getWorkflowById.mockResolvedValue({ success: true, data: theDto });
  api.getApprovalDirectory.mockResolvedValue({ roles: [], users: [] });
  api.getRunningInstanceCount.mockResolvedValue({ success: true, data: 0 });
  const wrapper = shallowMount(ApprovalWorkflowEditor, {
    props: { embedded: true, initialDecisionType: 'SALES_ORDER_APPROVAL', lockDecisionType: true },
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

const LINEAR_NODES = JSON.stringify([
  { id: 'start', type: 'start', label: '提交', config: {}, position: { x: 0, y: 0 } },
  { id: 'a1', type: 'approval', label: '财务审批', config: { approverRoles: ['finance_manager'] }, position: { x: 1, y: 0 } },
  { id: 'approved', type: 'end', label: '通过', config: {}, position: { x: 2, y: 0 } },
  { id: 'rejected', type: 'end', label: '驳回', config: {}, position: { x: 3, y: 0 } },
]);
const LINEAR_EDGES = JSON.stringify([
  { id: 'e1', source: 'start', target: 'a1', priority: 0 },
  { id: 'e2', source: 'a1', target: 'approved', condition: "#decision == 'APPROVE'", priority: 0 },
  { id: 'e3', source: 'a1', target: 'rejected', condition: "#decision == 'REJECT'", priority: 1 },
]);

describe('退回画布的横幅文案 —— 每种原因都必须精确匹配判定算出来的文案', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('阴性对照：完全线性、可读的图不显示任何退回横幅', async () => {
    const wrapper = await mountWithDto(makeDto(LINEAR_NODES, LINEAR_EDGES));
    expect(
      bannerDescription(wrapper),
      '不该退回画布却渲染了退回横幅',
    ).toBeUndefined();
  });

  it('branching-node：含条件节点，横幅精确等于该原因的文案', async () => {
    const nodes = JSON.stringify([
      { id: 'start', type: 'start', config: {}, position: { x: 0, y: 0 } },
      { id: 'c1', type: 'condition', config: {}, position: { x: 1, y: 0 } },
    ]);
    const edges = JSON.stringify([
      { id: 'e1', source: 'start', target: 'c1', priority: 0 },
    ]);
    const wrapper = await mountWithDto(makeDto(nodes, edges));
    expect(bannerDescription(wrapper)).toBe(STEP_LIST_FALLBACK_TEXT['branching-node']);
  });

  it('unknown-node（悬空连线，端点不存在）：横幅精确等于该原因的文案，⛔ 不是静默丢配置', async () => {
    const nodes = JSON.stringify([
      { id: 'start', type: 'start', config: {}, position: { x: 0, y: 0 } },
      { id: 'a1', type: 'approval', config: {}, position: { x: 1, y: 0 } },
    ]);
    const edges = JSON.stringify([
      { id: 'e1', source: 'start', target: 'a1', priority: 0 },
      { id: 'e2', source: 'a1', target: 'nope-does-not-exist', condition: "#decision == 'APPROVE'", priority: 0 },
    ]);
    const wrapper = await mountWithDto(makeDto(nodes, edges));
    expect(bannerDescription(wrapper)).toBe(STEP_LIST_FALLBACK_TEXT['unknown-node']);
  });

  it('no-approve-edge（审批节点缺通过出边）：横幅说的是"通过"，⛔ 不说成"有分支"（那是假话）', async () => {
    const nodes = JSON.stringify([
      { id: 'start', type: 'start', config: {}, position: { x: 0, y: 0 } },
      { id: 'a1', type: 'approval', config: {}, position: { x: 1, y: 0 } },
      { id: 'approved', type: 'end', config: {}, position: { x: 2, y: 0 } },
      { id: 'rejected', type: 'end', config: {}, position: { x: 3, y: 0 } },
    ]);
    const edges = JSON.stringify([
      { id: 'e1', source: 'start', target: 'a1', priority: 0 },
      { id: 'e2', source: 'a1', target: 'rejected', condition: "#decision == 'REJECT'", priority: 0 },
    ]);
    const wrapper = await mountWithDto(makeDto(nodes, edges));
    const description = bannerDescription(wrapper);
    expect(description).toBe(STEP_LIST_FALLBACK_TEXT['no-approve-edge']);
    expect(description, '把"缺通过出边"说成"有分支"是假话').not.toBe(STEP_LIST_FALLBACK_TEXT['branching-node']);
  });
});
