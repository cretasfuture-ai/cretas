/** Check the five-minute badge cache each minute while the page is visible. */
export function startVisibleBadgeRefresh(refresh: () => void, isVisible: () => boolean): () => void {
  const timer = window.setInterval(() => {
    if (isVisible()) refresh();
  }, 60_000);
  return () => window.clearInterval(timer);
}
