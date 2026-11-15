import { describe, expect, it } from 'vitest';
import { defineComponent, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { useAiEntryLink } from '../useAiEntryLink';
import { queueAiEntry } from '../aiEntryHandoff';

describe('business AI entry links', () => {
  it('clears consumed handoff text when the editor closes and does not replay it on ordinary reopen', async () => {
    const identity = { factoryId: 'WHOLESALE_TEST', actorId: '1709' };
    const target = queueAiEntry(identity, '/sales/orders', '为验收客户创建新订单');
    const visible = ref(false);
    let initialInput = ref('');
    const page = defineComponent({ setup() { initialInput = useAiEntryLink(visible, ref(true), () => identity); return () => null; } });
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/sales/orders', component: page }] });
    await router.push(target);
    const wrapper = mount(page, { global: { plugins: [router] } });
    await flushPromises();
    expect(initialInput.value).toBe('为验收客户创建新订单');
    visible.value = false;
    await flushPromises();
    visible.value = true;
    await flushPromises();
    expect(initialInput.value).toBe('');
    wrapper.unmount();
  });
  it.each([true, false])('opens only for writable users (%s), consumes only its own query parameter', async (writable) => {
    const visible = ref(false);
    const canWrite = ref(writable);
    const page = defineComponent({ setup() { useAiEntryLink(visible, canWrite); return () => null; } });
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/sales/orders', component: page }] });
    await router.push('/sales/orders?assistant=order-entry&status=DRAFT#orders');
    const wrapper = mount(page, { global: { plugins: [router] } });
    await flushPromises();
    expect(visible.value).toBe(writable);
    if (writable) {
      expect(router.currentRoute.value.query).toEqual({ status: 'DRAFT' });
      expect(router.currentRoute.value.hash).toBe('#orders');
      visible.value = false;
      await router.replace('/sales/orders?status=APPROVED');
      expect(visible.value).toBe(false);
    } else expect(router.currentRoute.value.query.assistant).toBe('order-entry');
    wrapper.unmount();
  });
});
