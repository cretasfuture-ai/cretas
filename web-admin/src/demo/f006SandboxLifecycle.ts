/** Recovery and creation share one client owner: a late null recovery cannot erase a new SID. */
let tail: Promise<void> = Promise.resolve()
export function serializeSandboxLifecycle<T>(work: () => Promise<T>): Promise<T> {
  const task = tail.then(work, work)
  tail = task.then((): void => undefined, (): void => undefined)
  return task
}
