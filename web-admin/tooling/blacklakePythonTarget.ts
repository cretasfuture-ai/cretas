/** Explicit local experiment target; the ordinary development/prod modes ignore it. */
export function pythonProxyTarget(mode: string, env: Record<string, string>): string {
  const isolated = mode === 'blacklake' ? env.VITE_BLACKLAKE_PYTHON_URL : undefined;
  if (isolated && !/^http:\/\/127\.0\.0\.1:(8093|8095)$/.test(isolated)) {
    throw new Error('Blacklake preview requires the approved loopback API port');
  }
  return isolated || env.VITE_PYTHON_URL || 'http://localhost:8083';
}
