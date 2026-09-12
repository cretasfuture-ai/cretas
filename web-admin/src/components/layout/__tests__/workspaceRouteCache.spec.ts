import { afterEach, beforeEach, expect, it } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import { defineComponent, h, onMounted, onUnmounted, ref } from 'vue';
import WorkspaceRouteCache from '../WorkspaceRouteCache.vue';

let wrapper: VueWrapper;
let mounted = 0;
let unmounted = 0;
const Draft = defineComponent({
  name: 'Draft',
  setup() {
    const value = ref('');
    onMounted(() => mounted++);
    onUnmounted(() => unmounted++);
    return () => h('input', { value: value.value, onInput: (event: Event) => { value.value = (event.target as HTMLInputElement).value; } });
  },
});
beforeEach(() => { mounted = 0; unmounted = 0; });
afterEach(() => wrapper?.unmount());
function start() {
  wrapper = mount(WorkspaceRouteCache, { props: { component: h(Draft), activeKey: 'original', cacheKeys: ['original'] } });
}
async function show(activeKey: string, cacheKeys: string[]) {
  await wrapper.setProps({ component: h(Draft), activeKey, cacheKeys });
  expect(wrapper.findAll('input')).toHaveLength(1);
}

it('retains eleven independent drafts while only the active form is mounted in the DOM', async () => {
  start();
  const keys = ['original'];
  await wrapper.get('input').setValue('first draft');
  for (let n = 1; n <= 10; n++) {
    keys.push(String(n));
    await show(String(n), [...keys]);
    await wrapper.get('input').setValue(`draft ${n}`);
  }
  for (const key of keys) {
    await show(key, [...keys]);
    expect(wrapper.get<HTMLInputElement>('input').element.value).toBe(key === 'original' ? 'first draft' : `draft ${key}`);
  }
  expect(mounted).toBe(11);
  expect(unmounted).toBe(0);
});

it('releases closed caches and does not accumulate orphan instances during repeated opening', async () => {
  start();
  await wrapper.get('input').setValue('protected draft');
  for (let n = 0; n < 25; n++) {
    await show(String(n), ['original', String(n)]);
    expect(mounted - unmounted).toBe(2);
  }
  await show('original', ['original']);
  expect(mounted - unmounted).toBe(1);
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('protected draft');
  await show('24', ['original', '24']);
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('');
});

it('refreshes an invalidated cache generation without replacing another draft', async () => {
  start();
  await wrapper.get('input').setValue('keep');
  await show('list', ['original', 'list']);
  await wrapper.get('input').setValue('stale');
  await show('original', ['original', 'list#1']);
  expect(unmounted).toBe(1);
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('keep');
  await show('list#1', ['original', 'list#1']);
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('');
});

it('destroys the previous scope when the owning factory key changes', async () => {
  const factory = ref('A');
  const Host = defineComponent({ setup: () => () => h(WorkspaceRouteCache, {
    key: factory.value, component: h(Draft), activeKey: 'original', cacheKeys: ['original'],
  }) });
  wrapper = mount(Host);
  await wrapper.get('input').setValue('factory A draft');
  factory.value = 'B';
  await wrapper.vm.$nextTick();
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('');
  expect(unmounted).toBe(1);
  expect(mounted).toBe(2);
});
