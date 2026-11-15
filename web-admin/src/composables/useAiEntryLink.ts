import { computed, ref, watch, type Ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { takeAiEntry, type AiEntryIdentity } from './aiEntryHandoff';

/** Navigation only: opens the existing editor; never parses or submits an order. */
export function useAiEntryLink(visible: Ref<boolean>, canWrite: Readonly<Ref<boolean>>, identity?: () => AiEntryIdentity) {
  const route = useRoute();
  const router = useRouter();
  const initialInput = ref('');
  const scope = computed(() => identity?.() || null);
  watch(() => [route.query.assistant, canWrite.value, scope.value?.factoryId, scope.value?.actorId] as const, ([intent, writable]) => {
    if (intent !== 'order-entry' || !writable) return;
    initialInput.value = typeof route.query.handoff === 'string' ? takeAiEntry(route.query.handoff, scope.value, route.path) : '';
    visible.value = true;
    const query = { ...route.query };
    delete query.assistant;
    delete query.handoff;
    void router.replace({ path: route.path, query, hash: route.hash });
  }, { immediate: true });
  return initialInput;
}
