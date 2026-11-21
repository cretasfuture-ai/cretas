import { getCurrentInstance, h, isVNode, onScopeDispose } from 'vue';
import { useOverlay } from '@nuxt/ui/composables/useOverlay';
import DOMPurify from 'dompurify';
import CompatMessageDialog from './CompatMessageDialog.vue';
import type { BoxMode, BoxOptions, BoxResult, Text } from './messageContracts';
import './messages.css';

type Level = 'success' | 'error' | 'warning' | 'info' | 'primary';
type ToastOptions = { message?: Text; title?: string; type?: Level; duration?: number; showClose?: boolean;
  dangerouslyUseHTMLString?: boolean; grouping?: boolean; onClose?: () => void;
  position?: string; offset?: number; customClass?: string };
type ToastInput = Text | ToastOptions;
function optionsFor(input: ToastInput): ToastOptions {
  return typeof input === 'string' || typeof input === 'function' || isVNode(input) ? { message: input } : input;
}

export function useCompatMessages() {
  const app = getCurrentInstance()?.appContext.app;
  const overlay = useOverlay();
  const active = new Set<() => void>();
  const toasts = new Set<() => void>();
  let toastPromise: Promise<ReturnType<typeof import('@nuxt/ui/composables/useToast')['useToast']>> | undefined;
  const toast = () => toastPromise ??= import('@nuxt/ui/composables/useToast')
    .then(({ useToast }) => app ? app.runWithContext(useToast) : useToast());
  const closeBoxes = () => { for (const close of active) close(); };
  onScopeDispose(closeBoxes);

  function notify(input: ToastInput, level?: Level, notification = false) {
    const options = optionsFor(input);
    const type = level ?? options.type ?? 'info';
    const raw = options.message;
    const value = options.dangerouslyUseHTMLString && typeof raw === 'string'
      ? () => h('span', { innerHTML: DOMPurify.sanitize(raw) }) : raw;
    const duration = options.duration ?? (type === 'error' ? 0 : notification ? 4500 : 3000);
    let closed = false;
    let id: string | number | undefined;
    const close = () => {
      if (closed) return;
      closed = true;
      toasts.delete(close);
      if (id) void toast().then(store => store.remove(id!));
    };
    toasts.add(close);
    void toast().then(store => {
      if (closed) return;
      const added = store.add({
        title: options.title,
        description: value,
        color: type,
        duration: duration === 0 ? Infinity : duration,
        close: options.showClose ?? (notification || type === 'error'),
        class: ['compat-message-toast', options.customClass],
        ...(options.grouping && typeof raw === 'string' ? { id: `compat-${type}-${raw}` } : {}),
        'onUpdate:open': (open: boolean) => {
          if (open) return;
          closed = true;
          toasts.delete(close);
          options.onClose?.();
        },
      });
      id = added.id;
    }).catch(error => { toasts.delete(close); console.error('Nuxt UI 消息显示失败', error); });
    return { close };
  }

  function toastService(notification: boolean) {
    return Object.assign((input: ToastInput) => notify(input, undefined, notification), {
      success: (input: ToastInput) => notify(input, 'success', notification),
      error: (input: ToastInput) => notify(input, 'error', notification),
      warning: (input: ToastInput) => notify(input, 'warning', notification),
      info: (input: ToastInput) => notify(input, 'info', notification),
      primary: (input: ToastInput) => notify(input, 'primary', notification),
      closeAll: () => { for (const close of toasts) close(); },
    });
  }

  async function box(mode: BoxMode, text: Text, titleOrOptions?: string | BoxOptions, optionsArg?: BoxOptions): Promise<BoxResult> {
    const title = typeof titleOrOptions === 'string' ? titleOrOptions : '提示';
    const options = typeof titleOrOptions === 'object' ? titleOrOptions : optionsArg ?? {};
    const message = options.dangerouslyUseHTMLString && typeof text === 'string' ? DOMPurify.sanitize(text) : text;
    const instance = overlay.create(CompatMessageDialog, { destroyOnClose: true, props: { mode, title, message, options } });
    const close = () => instance.close({ action: 'close', value: '' } satisfies BoxResult);
    active.add(close);
    let result: BoxResult;
    try {
      result = await instance.open() as BoxResult;
    } finally {
      active.delete(close);
    }
    result.action = result.action === 'close' && !options.distinguishCancelAndClose ? 'cancel' : result.action;
    if (options.callback) options.callback(result.action);
    else if (result.action !== 'confirm') throw result.action;
    return result;
  }
  type BoxArgs = [Text, (string | BoxOptions)?, BoxOptions?];
  const ElMessageBox = {
    confirm: async (...args: BoxArgs) => (await box('confirm', ...args)).action,
    alert: async (...args: BoxArgs) => (await box('alert', ...args)).action,
    prompt: (...args: BoxArgs) => box('prompt', ...args),
    close: closeBoxes,
  };
  return { ElMessage: toastService(false), ElNotification: toastService(true), ElMessageBox };
}
