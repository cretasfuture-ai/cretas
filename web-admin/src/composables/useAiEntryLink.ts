import { watch, type Ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

/** Navigation only: opens the existing editor; never parses or submits an order. */
export function useAiEntryLink(visible: Ref<boolean>, canWrite: Readonly<Ref<boolean>>) {
  const route = useRoute();
  const router = useRouter();
  watch(() => [route.query.assistant, canWrite.value] as const, ([intent, writable]) => {
    if (intent !== 'order-entry' || !writable) return;
    visible.value = true;
    const query = { ...route.query };
    delete query.assistant;
    void router.replace({ path: route.path, query, hash: route.hash });
  }, { immediate: true });
}
