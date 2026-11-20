import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import type { SourceIndividual } from '@/api/sourceIndividual';

/**
 * 源头个体档案列表 —— 行上的「撤销」(#1844 复审 M-C)。
 *
 * 后端 DELETE / revoke 现在必须带原因(请求体, ≤255)。旧按钮只弹「确认删除」再发无体 DELETE,
 * Java 上线后点了只会得到一句「请求体解析失败」。这里钉住真实页面上的链路:
 *   ① 点「撤销」先弹窗填原因; 没填原因 ⇒ 不发请求, 弹窗里说要填什么
 *   ② 填了原因 ⇒ revokeSourceIndividual(factoryId, id, 原因), 成功后关窗并刷新列表
 *   ③ 409(被批次/系谱引用) ⇒ 后端原话留在弹窗里, 弹窗不关, 列表不刷新
 */

const routeMock: { query: Record<string, string> } = { query: {} };
vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));

const listMock = vi.fn();
const revokeMock = vi.fn();
vi.mock('@/api/sourceIndividual', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/sourceIndividual')>()),
  listSourceIndividuals: (...args: unknown[]) => listMock(...args),
  revokeSourceIndividual: (...args: unknown[]) => revokeMock(...args),
}));
const requestMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }));
vi.mock('@/api/request', () => requestMock);

import SourceIndividualsPage from '../index.vue';

const ROW: SourceIndividual = { id: 'si-1', factoryId: 'F006', individualCode: 'SL-BULL-001' };

async function mountPage() {
  listMock.mockResolvedValue({
    success: true,
    data: { content: [ROW], page: 1, size: 20, totalElements: 1, totalPages: 1 },
    message: '',
  });
  const wrapper = mount(SourceIndividualsPage, { global: { plugins: [ElementPlus] }, attachTo: document.body });
  await flushPromises();
  return wrapper;
}

async function openDialog(wrapper: VueWrapper) {
  const open = wrapper.find('[data-activity-action="source-individual.revoke.open"]');
  expect(open.exists()).toBe(true);
  expect(open.text()).toBe('撤销');
  await open.trigger('click');
  await flushPromises();
}

/** el-dialog 会 teleport 到 body, 弹窗内的元素要从 body 上找。 */
function body() {
  return new DOMWrapper(document.body);
}

async function typeReason(_wrapper: VueWrapper, text: string) {
  // el-input 把非 class/style 的 attrs 透传到内部 textarea 上, 两种落点都认。
  const textarea = body().find('textarea[data-testid="revoke-reason"], [data-testid="revoke-reason"] textarea');
  expect(textarea.exists()).toBe(true);
  await textarea.setValue(text);
}

async function submit(_wrapper: VueWrapper) {
  await body().find('[data-testid="revoke-submit"]').trigger('click');
  await flushPromises();
}

beforeEach(() => {
  routeMock.query = {};
  listMock.mockReset();
  revokeMock.mockReset();
  Object.values(requestMock).forEach((m) => m.mockReset());
  document.body.innerHTML = '';
});

describe('源头个体档案 — 撤销要先填原因', () => {
  it('没填原因 ⇒ 不发请求, 弹窗里提示要填原因; 旧的无体 DELETE 一次都没发', async () => {
    const wrapper = await mountPage();
    await openDialog(wrapper);
    await typeReason(wrapper, '   ');
    await submit(wrapper);

    expect(revokeMock).not.toHaveBeenCalled();
    expect(requestMock.del).not.toHaveBeenCalled();
    expect(body().find('[data-testid="revoke-error"]').text()).toContain('请填写撤销原因');
    wrapper.unmount();
  });

  it('填了原因 ⇒ 带原因撤销, 成功后刷新列表', async () => {
    const wrapper = await mountPage();
    revokeMock.mockResolvedValue({ success: true, data: { ...ROW, revokeReason: '录入错误' }, message: '已撤销' });
    expect(listMock).toHaveBeenCalledTimes(1);

    await openDialog(wrapper);
    await typeReason(wrapper, ' 录入错误 ');
    await submit(wrapper);

    expect(revokeMock).toHaveBeenCalledWith('F006', 'si-1', '录入错误');
    expect(requestMock.del).not.toHaveBeenCalled();
    expect(listMock).toHaveBeenCalledTimes(2);
    expect(body().find('[data-testid="revoke-error"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('409 被批次引用 ⇒ 后端原话留在弹窗里, 不刷新列表', async () => {
    const wrapper = await mountPage();
    revokeMock.mockRejectedValue(new Error('已被批次 MB-20261118-001 使用，不能撤销'));

    await openDialog(wrapper);
    await typeReason(wrapper, '录入错误');
    await submit(wrapper);

    expect(revokeMock).toHaveBeenCalledTimes(1);
    expect(body().find('[data-testid="revoke-error"]').text()).toContain('MB-20261118-001');
    expect(body().find('[data-testid="revoke-submit"]').exists()).toBe(true);
    expect(listMock).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });
});
