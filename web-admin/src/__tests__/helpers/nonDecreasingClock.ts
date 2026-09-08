/** Preserve wall-clock values while preventing host clock corrections from going backwards. */
export function nonDecreasingClock(readWallClock: () => number): () => number {
  let last = Number.NEGATIVE_INFINITY;
  return () => {
    last = Math.max(last, readWallClock());
    return last;
  };
}

const originalClock = Symbol.for('cretas.test.original-wall-clock');
type ClockTarget = { now: () => number; [originalClock]?: () => number };

/** Vitest may reuse a worker; reinstall from its original reader, never stack wrappers. */
export function installNonDecreasingClock(target: ClockTarget): void {
  const read = target[originalClock] ?? target.now.bind(target);
  target[originalClock] = read;
  target.now = nonDecreasingClock(read);
}
