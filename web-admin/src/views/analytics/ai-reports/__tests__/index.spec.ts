import { describe, expect, it, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const route = { params: {} as Record<string, string> };
const router = { push: vi.fn(), replace: vi.fn() };
const auth = { factoryId: 'F006', factoryType: 'FACTORY', currentRole: 'factory_super_admin', user: { id: 1 } };
const permission = { canViewPrice: true };
const api = vi.hoisted(() => ({
  listAIReports: vi.fn(),
  getAIReport: vi.fn(),
  generateAIReport: vi.fn(),
  exportAIReport: vi.fn(),
}));

vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => router }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => permission }));
vi.mock('@/api/aiReports', () => api);
vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: { error: vi.fn(), success: vi.fn() },
}));

import ElementPlus from 'element-plus';
import ReportsView from '../index.vue';
const globalOptions = { global: { plugins: [ElementPlus] } };

const row = { id: 7, reportType: 'daily', periodStart: '2026-11-17', periodEnd: '2026-11-17', createdAt: '2026-11-17T20:00:00', generationStatus: 'SUCCESS' };

describe('AI 经营报告页', () => {
  beforeEach(() => { vi.clearAllMocks(); route.params = {}; });

  it('首次读取列表还没回来时不显示「暂无报告」，回来后显示行', async () => {
    let resolveList: (value: unknown) => void = () => {};
    api.listAIReports.mockReturnValue(new Promise((resolve) => { resolveList = resolve; }));
    const wrapper = mount(ReportsView, globalOptions);
    await flushPromises();
    expect(wrapper.text()).not.toContain('暂无报告');
    expect(wrapper.text()).toContain('报告加载中');
    resolveList({ success: true, data: { reports: [row], total: 1 } });
    await flushPromises();
    expect(wrapper.text()).toContain('日报');
    expect(wrapper.text()).not.toContain('暂无报告');
  });

  it('读完确实没有报告时才显示「暂无报告」（阴性对照）', async () => {
    api.listAIReports.mockResolvedValue({ success: true, data: { reports: [], total: 0 } });
    const wrapper = mount(ReportsView, globalOptions);
    await flushPromises();
    expect(wrapper.text()).toContain('暂无报告');
  });

  it('详情里提供 PDF 与 PPT 导出，并以服务端返回的文件触发下载', async () => {
    api.listAIReports.mockResolvedValue({ success: true, data: { reports: [row], total: 1 } });
    api.getAIReport.mockResolvedValue({ success: true, data: { reportId: 7, success: true, analysis: '正文', reportAnalysis: '含 AI 解读的正文', generationStatus: 'SUCCESS' } });
    api.exportAIReport.mockResolvedValue(new Blob(['%PDF'], { type: 'application/pdf' }));
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    const wrapper = mount(ReportsView, { ...globalOptions, attachTo: document.body });
    await flushPromises();
    await wrapper.find('tbody tr').trigger('click');
    await flushPromises();
    const buttons = Array.from(document.body.querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(buttons).toContain('导出 PDF');
    expect(buttons).toContain('导出 PPT');
    Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.includes('导出 PDF'))?.click();
    await flushPromises();
    expect(api.exportAIReport).toHaveBeenCalledWith('F006', 7, 'pdf');
    wrapper.unmount();
  });
});
