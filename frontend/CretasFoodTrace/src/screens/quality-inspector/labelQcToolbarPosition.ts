/** Project image coordinates into the unscaled canvas overlay, then keep controls visible. */
export function labelQcToolbarPosition(
  bbox: { xMin: number; xMax: number; yMin: number; yMax: number },
  surface: { width: number; height: number; left: number; top: number },
  viewport: { scale: number; translateX: number; translateY: number },
  container: { width: number; height: number },
) {
  const width = 230;
  const height = 50;
  const x = (fraction: number) => surface.left + surface.width / 2 + viewport.translateX
    + (fraction - 0.5) * surface.width * viewport.scale;
  const y = (fraction: number) => surface.top + surface.height / 2 + viewport.translateY
    + (fraction - 0.5) * surface.height * viewport.scale;
  const above = y(bbox.yMin) - height - 8;
  const preferred = above >= 4 ? above : y(bbox.yMax) + 7;
  return {
    width,
    left: Math.max(4, Math.min(x((bbox.xMin + bbox.xMax) / 2) - width / 2, container.width - width - 4)),
    top: Math.max(4, Math.min(preferred, container.height - height - 4)),
  };
}
