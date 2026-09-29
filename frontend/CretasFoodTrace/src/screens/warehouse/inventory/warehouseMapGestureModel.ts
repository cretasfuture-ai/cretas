export interface MapTransform {
  scale: number;
  translateX: number;
  translateY: number;
}

export function boundedMapOffset(offset: number, contentSize: number, viewportSize: number, scale: number): number {
  'worklet';
  const limit = Math.max(0, (contentSize * scale - viewportSize) / 2);
  return Math.max(-limit, Math.min(limit, offset));
}

export function pinchMapTransform(
  start: MapTransform,
  startFocal: { x: number; y: number },
  focal: { x: number; y: number },
  gestureScale: number,
  minScale: number,
  maxScale: number,
  content: { width: number; height: number },
  viewport: { width: number; height: number },
): MapTransform {
  'worklet';
  const scale = Math.max(minScale, Math.min(maxScale, start.scale * gestureScale));
  const ratio = scale / start.scale;
  const x = focal.x - viewport.width / 2
    - (startFocal.x - viewport.width / 2 - start.translateX) * ratio;
  const y = focal.y - viewport.height / 2
    - (startFocal.y - viewport.height / 2 - start.translateY) * ratio;
  return {
    scale,
    translateX: boundedMapOffset(x, content.width, viewport.width, scale),
    translateY: boundedMapOffset(y, content.height, viewport.height, scale),
  };
}
