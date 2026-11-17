/**
 * 分析报告分享: 后端没有 /smart-bi/share (2026-11-18 核实), 弹窗必须明确「暂不支持」,
 * ⛔ 不发请求、不生成假链接。
 */
import { describe, it, expect, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

const postMock = vi.fn();
vi.mock('@/api/request', () => ({ post: (...a: unknown[]) => postMock(...a) }));
vi.mock('@/components/dialog', () => ({
  DesktopModal: {
    props: ['modelValue', 'contextLabel'],
    template: '<div v-if="modelValue" class="modal"><span class="ctx">{{ contextLabel }}</span><slot /></div>',
  },
}));

import ShareDialog from '../ShareDialog.vue';

describe('ShareDialog —— 暂不支持分享', () => {
  it('打开后提示暂不支持, 不请求后端, 不出现链接与生成按钮', async () => {
    const wrapper = mount(ShareDialog, {
      props: { factoryId: 'F006', activeTab: '0' },
      global: { stubs: { 'el-result': { props: ['title', 'subTitle'], template: '<div class="el-result">{{ title }}|{{ subTitle }}</div>' } } },
    });
    (wrapper.vm as unknown as { open: (b: unknown) => void }).open({ uploadId: 7, fileName: '销售.xlsx' });
    await nextTick();

    expect(wrapper.find('.el-result').text()).toContain('暂不支持分享');
    expect(wrapper.find('.ctx').text()).toContain('销售.xlsx (#7)');
    expect(wrapper.text()).not.toContain('生成分享链接');
    expect(postMock).not.toHaveBeenCalled();
  });

  it('阴性对照: 未调用 open 时弹窗不渲染', () => {
    const wrapper = mount(ShareDialog, { props: { factoryId: 'F006', activeTab: '0' } });
    expect(wrapper.find('.modal').exists()).toBe(false);
  });
});
