import * as Location from 'expo-location';

/** A fresh native location fix with a deadline and a removable listener. */
export function getCurrentPositionWithTimeout(
  options: Location.LocationOptions,
  signal?: AbortSignal,
  timeoutMs = 15000,
): Promise<Location.LocationObject> {
  return new Promise((resolve, reject) => {
    let finished = false;
    let subscription: Location.LocationSubscription | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const cleanup = () => {
      if (timer !== undefined) clearTimeout(timer);
      signal?.removeEventListener('abort', cancel);
      subscription?.remove();
    };
    const fail = (error: Error) => {
      if (finished) return;
      finished = true;
      cleanup();
      reject(error);
    };
    const cancel = () => fail(new Error('Location request cancelled'));
    if (signal?.aborted) {
      cancel();
      return;
    }
    signal?.addEventListener('abort', cancel, { once: true });
    timer = setTimeout(() => fail(new Error('Location request timed out')), timeoutMs);
    Location.watchPositionAsync(options, location => {
      if (finished) return;
      finished = true;
      cleanup();
      resolve(location);
    }, reason => fail(new Error(reason))).then(listener => {
      subscription = listener;
      if (finished) listener.remove();
    }).catch(error => fail(error instanceof Error ? error : new Error('Location provider failed')));
  });
}
