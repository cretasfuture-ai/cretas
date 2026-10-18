/** Shared geometry extracted from VisionLab's tray annotator.
 * No factory classes, images, network requests or persistence belong here.
 * Both the Vue editor and the existing local annotator import this file.
 */
export const clamp = value => Math.max(0, Math.min(1, value));

export function corners(box) {
  return [[box[0], box[1]], [box[2], box[1]], [box[2], box[3]], [box[0], box[3]]];
}

export function hitBox(boxes, x, y, width, height, tolerance = 12) {
  for (let index = boxes.length - 1; index >= 0; index--) {
    const box = boxes[index];
    const corner = corners(box).findIndex(([cx, cy]) => Math.hypot((x - cx) * width, (y - cy) * height) <= tolerance);
    if (corner >= 0) return { index, mode: 'resize', corner };
    if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3]) return { index, mode: 'move', corner: -1 };
  }
  return { index: -1, mode: 'new', corner: -1 };
}

export function moveBox(box, dx, dy) {
  const width = box[2] - box[0], height = box[3] - box[1];
  const x = Math.min(clamp(box[0] + dx), 1 - width), y = Math.min(clamp(box[1] + dy), 1 - height);
  return [x, y, x + width, y + height];
}

export function resizeBox(box, corner, x, y) {
  const result = box.slice();
  result[corner === 0 || corner === 3 ? 0 : 2] = clamp(x);
  result[corner === 0 || corner === 1 ? 1 : 3] = clamp(y);
  return result;
}

export function normaliseBox(box, minimum = 0.003) {
  const result = [clamp(Math.min(box[0], box[2])), clamp(Math.min(box[1], box[3])),
    clamp(Math.max(box[0], box[2])), clamp(Math.max(box[1], box[3]))];
  return result.every(Number.isFinite) && result[2] - result[0] >= minimum && result[3] - result[1] >= minimum ? result : null;
}

export function zoomAnchor(scroll, local, size) {
  return (scroll + local) / Math.max(size, 1);
}

export function createBoxHistory(limit = 50) {
  const snapshots = [];
  const copy = boxes => boxes.map(box => Array.isArray(box) ? box.slice() : { ...box });
  return {
    get length() { return snapshots.length; },
    push(boxes) { snapshots.push(copy(boxes)); if (snapshots.length > limit) snapshots.shift(); },
    pop() { const boxes = snapshots.pop(); return boxes ? copy(boxes) : null; },
    clear() { snapshots.length = 0; },
  };
}
