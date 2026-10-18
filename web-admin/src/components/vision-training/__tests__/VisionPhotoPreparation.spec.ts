import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import VisionPhotoPreparation from '../VisionPhotoPreparation.vue';
import PhotoRegionEditor from '../PhotoRegionEditor.vue';
import * as api from '@/api/visionPreparation';
import type { VisionPreparationState } from '@/api/visionPreparation';

vi.mock('@/api/visionPreparation', () => ({
  getVisionPreparation: vi.fn(), getVisionPreparationImage: vi.fn(), uploadVisionPreparationPhoto: vi.fn(),
  reviewVisionPreparationPhoto: vi.fn(), saveVisionPreparationStandard: vi.fn(), submitVisionPreparation: vi.fn(),
}));

function state(overrides: Partial<VisionPreparationState> = {}): VisionPreparationState {
  return {
    revision: 1, taskFamily: 'packaging', photos: [{ photoId: 'p1', fileName: '一号.jpg', purpose: 'training', captureGroup: '今天', width: 100, height: 100, verdict: null, reviewed: false, boxes: [], transcription: '', imageSha256: 'a' }],
    standard: { passCriteria: '完整', failCriteria: '破损', maxMissRate: 2, maxFalseAlarmRate: 3, confirmed: true, confirmedBy: 'u1', confirmedAt: 'now' }, blockers: [], readyToSubmit: true, submission: null,
    limits: { maxBytes: 5 * 1024 * 1024, maxPhotos: 200, minTrainingPhotos: 1, minAcceptancePhotos: 1 }, labels: [{ id: '0', name: '封口' }], ...overrides,
  };
}
function mountComponent() { return mount(VisionPhotoPreparation, { props: { factoryId: 'F1', projectId: 'P1' } }); }
beforeEach(() => {
  vi.resetAllMocks(); vi.mocked(api.getVisionPreparation).mockResolvedValue(state()); vi.mocked(api.getVisionPreparationImage).mockResolvedValue({ contentBase64: 'abc', mimeType: 'image/jpeg' });
});

describe('VisionPhotoPreparation', () => {
  it('allows browsing frozen photos while keeping annotations and review writes disabled', async () => {
    const frozen = state({ submission: { submissionId: 's1', snapshotSha256: 'h', status: 'queued', executionProjectId: 'execution-a', stage: 'train', jobStatus: 'queued', message: '等待处理' } });
    frozen.photos = [1, 2].map(n => ({ ...frozen.photos[0]!, photoId: `p${n}`, fileName: `${n}号.jpg`, reviewed: true, verdict: 'good', boxes: [{ label: '0', x0: 0.1, y0: 0.2, x1: 0.6, y1: 0.7 }] }));
    vi.mocked(api.getVisionPreparation).mockResolvedValue(frozen);
    const wrapper = mountComponent(); await flushPromises();
    await wrapper.findAll('.step-button')[1]!.trigger('click'); await flushPromises();
    expect(wrapper.get('#review-photo-picker').attributes('disabled')).toBeUndefined();
    await wrapper.get('#review-photo-picker').setValue('p2'); await flushPromises();
    expect(api.getVisionPreparationImage).toHaveBeenLastCalledWith('F1', 'P1', 'p2');
    expect(wrapper.findComponent(PhotoRegionEditor).props('disabled')).toBe(true);
    expect(wrapper.get('[data-testid="preparation-step-review"] .primary-button').attributes('disabled')).toBeDefined();
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined();
    expect(wrapper.get('#photo-transcription').attributes('disabled')).toBeDefined();
    expect(api.reviewVisionPreparationPhoto).not.toHaveBeenCalled();
  });

  it('exposes the server-linked execution with its owning factory and source project', async () => {
    vi.mocked(api.getVisionPreparation).mockResolvedValue(state({ submission: { submissionId: 's1', snapshotSha256: 'h', status: 'queued', executionProjectId: 'execution-a', stage: 'train', jobStatus: 'queued', message: '等待处理' } }));
    const wrapper = mountComponent(); await flushPromises();
    expect(wrapper.emitted('execution-linked')).toEqual([[{ factoryId: 'F1', projectId: 'P1', executionProjectId: 'execution-a' }]]);
  });

  it('keeps the submission step when rereading completed dataset progress', async () => {
    const queued = state({ canContinueTraining: false, submission: { submissionId: 's1', snapshotSha256: 'h', status: 'queued', stage: 'dataset', jobStatus: 'queued', message: '正在整理照片' } });
    const completed = state({ ...queued, canContinueTraining: true, submission: { ...queued.submission!, jobStatus: 'succeeded', message: '学习数据已整理，请提交本厂模型学习。' } });
    vi.mocked(api.getVisionPreparation).mockResolvedValueOnce(queued).mockResolvedValueOnce(completed);
    const wrapper = mountComponent(); await flushPromises();
    await wrapper.findAll('.step-button')[3]!.trigger('click');
    await wrapper.get('.heading-row button').trigger('click'); await flushPromises();
    expect(wrapper.find('[data-testid="preparation-step-submit"]').exists()).toBe(true);
    expect(wrapper.get('[data-testid="submit-preparation"]').text()).toContain('提交本厂模型学习');
    expect(api.submitVisionPreparation).not.toHaveBeenCalled();
  });

  it('rereads the selected review photo but resets navigation when the project changes', async () => {
    const initial = state();
    initial.photos.push({ ...initial.photos[0]!, photoId: 'p2', fileName: '二号.jpg' });
    vi.mocked(api.getVisionPreparation).mockResolvedValue(initial);
    const wrapper = mountComponent(); await flushPromises();
    await wrapper.findAll('.step-button')[1]!.trigger('click'); await flushPromises();
    await wrapper.get('#review-photo-picker').setValue('p2'); await flushPromises();
    await wrapper.get('.heading-row button').trigger('click'); await flushPromises();
    expect(wrapper.find('[data-testid="preparation-step-review"]').exists()).toBe(true);
    expect(wrapper.get('#review-photo-picker').element).toHaveProperty('value', 'p2');
    expect(api.getVisionPreparationImage).toHaveBeenLastCalledWith('F1', 'P1', 'p2');
    await wrapper.setProps({ factoryId: 'F2', projectId: 'P2' }); await flushPromises();
    expect(wrapper.find('[data-testid="preparation-step-upload"]').exists()).toBe(true);
    expect(api.getVisionPreparation).toHaveBeenLastCalledWith('F2', 'P2');
    expect(api.reviewVisionPreparationPhoto).not.toHaveBeenCalled();
  });

  it('loads the selected photo only when entering the review step and keeps four-step navigation', async () => {
    const wrapper = mountComponent(); await flushPromises();
    expect(api.getVisionPreparation).toHaveBeenCalledWith('F1', 'P1');
    expect(wrapper.find('[data-testid="preparation-step-upload"]').exists()).toBe(true);
    expect(wrapper.findAll('.step-button')).toHaveLength(4);
    await wrapper.findAll('.step-button')[1]!.trigger('click'); await flushPromises();
    expect(api.getVisionPreparationImage).toHaveBeenCalledWith('F1', 'P1', 'p1');
  });

  it('freezes every editing action after submission and shows waiting configuration truthfully', async () => {
    vi.mocked(api.getVisionPreparation).mockResolvedValue(state({ submission: { submissionId: 's1', snapshotSha256: 'h', status: 'waiting_configuration', message: '已提交' } }));
    const wrapper = mountComponent(); await flushPromises();
    expect(wrapper.text()).toContain('资料已提交，等待本厂执行设置接通，尚未开始训练');
    expect(wrapper.findAll('input').some(input => input.attributes('disabled') === undefined)).toBe(false);
    await wrapper.findAll('.step-button')[3]!.trigger('click');
    expect(wrapper.get('[data-testid="submit-preparation"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.get('[data-testid="submit-preparation"]').text()).toContain('重新核对本厂执行设置');
  });

  it('retains review input after a revision conflict and does not retry automatically', async () => {
    const conflict = Object.assign(new Error('版本已变化'), { status: 409 }); vi.mocked(api.reviewVisionPreparationPhoto).mockRejectedValue(conflict);
    const wrapper = mountComponent(); await flushPromises(); await wrapper.findAll('.step-button')[1]!.trigger('click'); await flushPromises();
    await wrapper.findAll('input[type="radio"]')[2]!.setValue(); expect(wrapper.get('[data-testid="preparation-step-review"] .primary-button').attributes('disabled')).toBeUndefined(); await wrapper.get('[data-testid="preparation-step-review"] .primary-button').trigger('click'); await flushPromises();
    expect(api.reviewVisionPreparationPhoto).toHaveBeenCalledTimes(1); expect(wrapper.text()).toContain('版本已变化'); expect(wrapper.get('input[type="radio"]').element).toBeTruthy();
  });

  it('does not allow saving a verdict when the selected image failed to load', async () => {
    vi.mocked(api.getVisionPreparationImage).mockRejectedValueOnce(new Error('照片读取失败'));
    const wrapper = mountComponent(); await flushPromises(); await wrapper.findAll('.step-button')[1]!.trigger('click'); await flushPromises();
    await wrapper.findAll('input[type="radio"]')[2]!.setValue();
    expect(wrapper.get('[data-testid="preparation-step-review"] .primary-button').attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toContain('照片读取失败');
  });

  it('enforces the Chinese quality standard boundary in the form', async () => {
    const wrapper = mountComponent(); await flushPromises(); await wrapper.findAll('.step-button')[2]!.trigger('click');
    expect(wrapper.get('#pass-criteria').attributes('maxlength')).toBe('2000');
    expect(wrapper.get('#fail-criteria').attributes('maxlength')).toBe('2000');
    await wrapper.get('#pass-criteria').setValue('合'); await wrapper.get('#fail-criteria').setValue('坏');
    expect(wrapper.get('[data-testid="preparation-step-standard"] .primary-button').attributes('disabled')).toBeDefined();
  });

  it('allows an explicit second submission only when the server says training can continue', async () => {
    const queued = state({ canContinueTraining: true, submission: { submissionId: 's1', snapshotSha256: 'h', status: 'queued', stage: 'dataset', jobStatus: 'succeeded', message: '学习数据已整理，请提交本厂模型学习。' } });
    const submitted = state({ canContinueTraining: false, submission: { ...queued.submission!, stage: 'train', jobStatus: 'queued', message: '已提交本厂模型学习，尚未完成训练。' } });
    vi.mocked(api.getVisionPreparation).mockResolvedValueOnce(queued).mockResolvedValueOnce(submitted);
    vi.mocked(api.submitVisionPreparation).mockResolvedValue(submitted);
    const wrapper = mountComponent(); await flushPromises(); await wrapper.findAll('.step-button')[3]!.trigger('click');
    expect(wrapper.text()).toContain('学习数据已整理，请提交本厂模型学习。');
    expect(wrapper.get('[data-testid="submit-preparation"]').text()).toContain('提交本厂模型学习');
    await wrapper.get('[data-testid="submit-preparation"]').trigger('click'); await flushPromises();
    expect(api.submitVisionPreparation).toHaveBeenCalledWith('F1', 'P1', 1);
    expect(api.getVisionPreparation).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('尚未完成训练');
    expect(wrapper.text()).not.toContain('训练完成');
  });

  it('shows cumulative photo storage in Chinese and disables both upload pickers at the server limit', async () => {
    const limited = state();
    limited.limits.maxStoredBytes = 512 * 1024 * 1024;
    limited.limits.storedBytes = 512 * 1024 * 1024;
    vi.mocked(api.getVisionPreparation).mockResolvedValue(limited);
    const wrapper = mountComponent(); await flushPromises();
    expect(wrapper.get('[data-testid="storage-budget"]').text()).toContain('512 兆 / 512 兆');
    expect(wrapper.get('[data-testid="storage-limit-message"]').text()).toContain('已达到照片存储上限');
    expect(wrapper.findAll('input[type="file"]').every(input => input.attributes('disabled') !== undefined)).toBe(true);
  });

  it('recovers state after a failed submit without retrying the POST', async () => {
    const frozen = state({ submission: { submissionId: 's1', snapshotSha256: 'h', status: 'waiting_configuration', message: '本厂资料已冻结，尚未开始训练。' } });
    vi.mocked(api.getVisionPreparation).mockResolvedValueOnce(state()).mockResolvedValueOnce(frozen);
    vi.mocked(api.submitVisionPreparation).mockRejectedValueOnce(new Error('提交连接中断'));
    const wrapper = mountComponent(); await flushPromises(); await wrapper.findAll('.step-button')[3]!.trigger('click');
    await wrapper.get('[data-testid="submit-preparation"]').trigger('click'); await flushPromises();
    expect(api.submitVisionPreparation).toHaveBeenCalledTimes(1);
    expect(api.getVisionPreparation).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="preparation-frozen"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('提交连接中断');
  });

  it('keeps the confirmed standard visible after the preparation becomes frozen', async () => {
    const frozen = state({ submission: { submissionId: 's1', snapshotSha256: 'h', status: 'waiting_configuration', message: '资料已冻结' } });
    vi.mocked(api.getVisionPreparation).mockResolvedValueOnce(state()).mockResolvedValueOnce(frozen);
    vi.mocked(api.submitVisionPreparation).mockResolvedValueOnce(frozen);
    const wrapper = mountComponent(); await flushPromises();
    await wrapper.findAll('.step-button')[3]!.trigger('click');
    await wrapper.get('[data-testid="submit-preparation"]').trigger('click'); await flushPromises();
    await wrapper.findAll('.step-button')[2]!.trigger('click');
    expect(wrapper.get('input[type="checkbox"]').element).toHaveProperty('checked', true);
    expect(wrapper.text()).not.toContain('照片或审核结果有变化');
    expect(wrapper.get('#pass-criteria').attributes('disabled')).toBeDefined();
  });

  it('keeps review navigation disabled while the current judgement is dirty and advances after an explicit save', async () => {
    const initial = state();
    initial.photos.push({ ...initial.photos[0]!, photoId: 'p2', fileName: '二号.jpg', reviewed: false });
    const reviewed = state({ revision: 2, photos: [{ ...initial.photos[0]!, verdict: 'uncertain', reviewed: true }, initial.photos[1]!] });
    vi.mocked(api.getVisionPreparation).mockResolvedValue(initial);
    vi.mocked(api.reviewVisionPreparationPhoto).mockResolvedValue(reviewed);
    const wrapper = mountComponent(); await flushPromises(); await wrapper.get('[data-testid="next-to-review"]').trigger('click'); await flushPromises();
    await wrapper.findAll('input[type="radio"]')[2]!.setValue();
    expect(wrapper.get('#review-photo-picker').attributes('disabled')).toBeDefined();
    expect(wrapper.findAll('.review-switches button').every(button => button.attributes('disabled') !== undefined)).toBe(true);
    await wrapper.get('[data-testid="preparation-step-review"] .primary-button').trigger('click'); await flushPromises();
    expect(wrapper.get('#review-photo-picker').element).toHaveProperty('value', 'p2');
    expect(wrapper.text()).toContain('二号.jpg');
  });

  it('shows the next standard step after every photo is explicitly reviewed', async () => {
    const initial = state();
    initial.photos.push({ ...initial.photos[0]!, photoId: 'p2', fileName: '二号.jpg', reviewed: false });
    const reviewed = state({ revision: 2, photos: initial.photos.map(photo => ({ ...photo, verdict: 'uncertain' as const, reviewed: true })) });
    vi.mocked(api.getVisionPreparation).mockResolvedValue(initial);
    vi.mocked(api.reviewVisionPreparationPhoto).mockResolvedValue(reviewed);
    const wrapper = mountComponent(); await flushPromises(); await wrapper.get('[data-testid="next-to-review"]').trigger('click'); await flushPromises();
    await wrapper.findAll('input[type="radio"]')[2]!.setValue(); await wrapper.get('[data-testid="preparation-step-review"] .primary-button').trigger('click'); await flushPromises();
    expect(wrapper.find('[data-testid="next-to-standard"]').exists()).toBe(true);
  });

  it('advances after the server serializes saved box coordinates in a different key order', async () => {
    const initial = state();
    initial.photos.push({ ...initial.photos[0]!, photoId: 'p2', fileName: '二号.jpg' });
    const drawn = { label: '0', x0: 0.1, y0: 0.2, x1: 0.6, y1: 0.7 };
    const returned = { label: '0', x0: 0.1, x1: 0.6, y0: 0.2, y1: 0.7 };
    vi.mocked(api.getVisionPreparation).mockResolvedValue(initial);
    vi.mocked(api.reviewVisionPreparationPhoto).mockResolvedValue(state({ revision: 2,
      photos: [{ ...initial.photos[0]!, verdict: 'good', reviewed: true, boxes: [returned] }, initial.photos[1]!] }));
    const wrapper = mountComponent(); await flushPromises();
    await wrapper.get('[data-testid="next-to-review"]').trigger('click'); await flushPromises();
    wrapper.findComponent(PhotoRegionEditor).vm.$emit('update:boxes', [drawn]);
    await wrapper.findAll('input[type="radio"]')[0]!.setValue();
    await wrapper.get('[data-testid="preparation-step-review"] .primary-button').trigger('click'); await flushPromises();
    expect(wrapper.get('#review-photo-picker').element).toHaveProperty('value', 'p2');
    expect(wrapper.text()).not.toContain('尚未保存');
  });
});
