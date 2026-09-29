import type {
  WarehouseAnnotation, WarehouseAnnotationKind, WarehouseFloor, WarehouseLayout,
  WarehouseLocation, WarehouseRack, WarehouseRectGeometry, WarehouseZone, WarehouseZoneShape,
} from '@/api/warehouseLayout';

export const EMPTY_WAREHOUSE_LAYOUT: WarehouseLayout = { zones: [], locations: [], floors: [] };

export function normalizeWarehouseLayout(value: unknown): WarehouseLayout {
  if (!value || typeof value !== 'object') return structuredClone(EMPTY_WAREHOUSE_LAYOUT);
  const layout = value as Partial<WarehouseLayout>;
  return {
    zones: Array.isArray(layout.zones) ? layout.zones : [],
    locations: Array.isArray(layout.locations) ? layout.locations : [],
    floors: Array.isArray(layout.floors)
      ? layout.floors.map((floor) => ({
        ...floor,
        racks: Array.isArray(floor.racks) ? floor.racks : [],
        // 两层分开归一化: 结构层(区域几何) / 标注层(自由图形)。
        // ⛔ 不要合并 —— 合并之后「这批货在哪个区」就会命中一个随手画的圈。
        zoneShapes: normalizeZoneShapes((floor as Partial<WarehouseFloor>).zoneShapes),
        annotations: normalizeAnnotations((floor as Partial<WarehouseFloor>).annotations),
      }))
      : [],
  };
}

// ───────────────────────── 平面图几何：结构层 + 标注层 ─────────────────────────

/** 区域/矩形/圆的最小边长，避免用户手一抖画出一个点不中的 0×0 图形。 */
export const MIN_SHAPE_SIZE = 24;
/**
 * 单笔自由笔画的点数上限。
 *
 * 🔴 为什么必须有: persist() PUT 的是**整份** layout(还含楼层的 base64 底图)。
 * pointermove 在触控板上一秒能给出上百个点, 不设上限时一笔涂画就能往
 * factory_feature_config.config 里塞进几十万个数字, 而那是所有租户共用的
 * JSONB 配置表。超限时按 thinPoints 抽稀而不是拒绝落笔 —— 拒绝落笔会让
 * 用户画到一半突然断线, 看起来像功能坏了。
 */
export const MAX_FREEHAND_POINTS = 400;
/** 单层标注数量上限，同上，防止一层图纸把整份配置撑爆。 */
export const MAX_ANNOTATIONS_PER_FLOOR = 300;
/** 自由笔相邻两点的最小间距(画布单位)，低于它认为是同一个点，不入库。 */
export const FREEHAND_MIN_STEP = 2;

export const ANNOTATION_KINDS: readonly WarehouseAnnotationKind[] = ['rect', 'ellipse', 'polygon', 'freehand', 'text'];

/**
 * 平面图工具。`select` 是默认态（选中/拖动/缩放已有图形），其余都是「落笔」态。
 * ⛔ 定义放在模型模块而不是 .vue 里 —— `<script setup>` 不允许 ES 导出。
 */
export type MapTool = 'select' | 'zone' | 'rect' | 'ellipse' | 'polygon' | 'freehand' | 'text';

export type ResizeHandle = 'nw' | 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w';
export const RESIZE_HANDLES: readonly ResizeHandle[] = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'];
export const TEXT_RESIZE_HANDLES: readonly ResizeHandle[] = ['nw', 'ne', 'se', 'sw'];
export interface FlowPoint { x: number; y: number }
export interface ViewportTransform { x: number; y: number; zoom: number }

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

/**
 * ⛔ 故意**不写成** `value is WarehouseRectGeometry` 类型守卫: 那会把入参收窄成
 * 「只有 x/y/width/height」, 后面再读 kind / points / zoneId 就全成了类型错误
 * —— 守卫是在断言「它至少有这四个数」, 不是在断言「它只有这四个数」。
 */
function hasFiniteRect(value: unknown): boolean {
  if (!value || typeof value !== 'object') return false;
  const rect = value as Partial<WarehouseRectGeometry>;
  return isFiniteNumber(rect.x) && isFiniteNumber(rect.y) && isFiniteNumber(rect.width) && isFiniteNumber(rect.height);
}

/**
 * 折线/自由笔的最小边长是 1 而不是 MIN_SHAPE_SIZE ——
 * 一条水平直线的包围盒高度**合法地**是 0，把它撑到 24 会让线条自己变形。
 */
export function minSizeForKind(kind: WarehouseAnnotationKind): number {
  return kind === 'polygon' || kind === 'freehand' ? 1 : MIN_SHAPE_SIZE;
}

/** 收敛成可渲染的矩形：坐标非负、边长不小于 min、全部取整。 */
export function clampRect(rect: WarehouseRectGeometry, min = MIN_SHAPE_SIZE): WarehouseRectGeometry {
  return {
    x: Math.max(0, Math.round(rect.x)),
    y: Math.max(0, Math.round(rect.y)),
    width: Math.max(min, Math.round(rect.width)),
    height: Math.max(min, Math.round(rect.height)),
  };
}

/** 拉框：起点终点任意方向都归一成左上角 + 正的宽高。 */
export function rectFromDrag(start: FlowPoint, end: FlowPoint, min = MIN_SHAPE_SIZE): WarehouseRectGeometry {
  return clampRect({
    x: Math.min(start.x, end.x),
    y: Math.min(start.y, end.y),
    width: Math.abs(end.x - start.x),
    height: Math.abs(end.y - start.y),
  }, min);
}

/** 整体平移：⛔ 只改 x/y，宽高一个像素都不许动。 */
export function moveRect<T extends WarehouseRectGeometry>(rect: T, dx: number, dy: number): T {
  return { ...rect, x: Math.max(0, Math.round(rect.x + dx)), y: Math.max(0, Math.round(rect.y + dy)) };
}

/**
 * 拖八个控制点缩放。
 *
 * 往回拖过头时**对边固定不动**：宽度停在 min，左边界也跟着停 ——
 * 否则图形会翻面（x 越过右边界，width 变负）。
 */
export function resizeRect<T extends WarehouseRectGeometry>(rect: T, handle: ResizeHandle, dx: number, dy: number, min = MIN_SHAPE_SIZE): T {
  let { x, y, width, height } = rect;
  if (handle.includes('w')) {
    const right = x + width;
    x = Math.min(Math.max(0, x + dx), right - min);
    width = right - x;
  } else if (handle.includes('e')) {
    width = Math.max(min, width + dx);
  }
  if (handle.includes('n')) {
    const bottom = y + height;
    y = Math.min(Math.max(0, y + dy), bottom - min);
    height = bottom - y;
  } else if (handle.includes('s')) {
    height = Math.max(min, height + dy);
  }
  return { ...rect, x: Math.round(x), y: Math.round(y), width: Math.round(width), height: Math.round(height) };
}

/**
 * 文字标注用四角等比缩放：框和字号一起变，避免只拉大框却留下固定字号。
 * 取宽高缩放比中较小者，保证文字仍能放进用户画出的框里。
 */
export function resizeAnnotation(
  annotation: WarehouseAnnotation,
  handle: ResizeHandle,
  dx: number,
  dy: number,
): WarehouseAnnotation {
  const resized = resizeRect(annotation, handle, dx, dy, minSizeForKind(annotation.kind));
  if (annotation.kind !== 'text') return resized;
  const scale = Math.min(resized.width / annotation.width, resized.height / annotation.height);
  return {
    ...resized,
    fontSize: Math.max(1, Math.round((annotation.fontSize || 16) * scale)),
  };
}

/** 八个缩放控制点的锚点坐标（画布坐标）。 */
export function handleAnchor(rect: WarehouseRectGeometry, handle: ResizeHandle): FlowPoint {
  return {
    x: handle.includes('w') ? rect.x : handle.includes('e') ? rect.x + rect.width : rect.x + rect.width / 2,
    y: handle.includes('n') ? rect.y : handle.includes('s') ? rect.y + rect.height : rect.y + rect.height / 2,
  };
}

/**
 * 屏幕坐标 → 画布坐标。
 *
 * 🔴 抽成纯函数的理由与 createTrailingDebouncer 同源: 浏览器自动化驱动不了
 * 画布上的指针交互(合成 PointerEvent 打不进 d3-drag), 「拖了但没动」既不能
 * 证明换算对也不能证明它错。换算是纯算术, 在这里确定性地验。
 */
export function screenToFlowPoint(clientX: number, clientY: number, bounds: { left: number; top: number }, viewport: ViewportTransform): FlowPoint {
  const zoom = viewport.zoom || 1;
  return { x: (clientX - bounds.left - viewport.x) / zoom, y: (clientY - bounds.top - viewport.y) / zoom };
}

/** 扁平数组 [x0,y0,x1,y1,...] → 点对。奇数长度时丢掉最后那个孤零零的坐标。 */
export function toPointPairs(flat: number[]): Array<[number, number]> {
  const pairs: Array<[number, number]> = [];
  for (let index = 0; index + 1 < flat.length; index += 2) {
    if (isFiniteNumber(flat[index]) && isFiniteNumber(flat[index + 1])) pairs.push([flat[index], flat[index + 1]]);
  }
  return pairs;
}

/** 抽稀到 maxPoints 以内：首尾必留，中间每轮砍一半。 */
export function thinPoints(flat: number[], maxPoints = MAX_FREEHAND_POINTS): number[] {
  let pairs = toPointPairs(flat);
  while (pairs.length > maxPoints && pairs.length > 2) {
    const kept = pairs.filter((_, index) => index % 2 === 0 || index === pairs.length - 1);
    if (kept.length >= pairs.length) break; // 防死循环：砍不动就停
    pairs = kept;
  }
  return pairs.flatMap(([x, y]) => [x, y]);
}

/** 自由笔落点。间距太小视为同一点直接丢弃；超过上限就抽稀，⛔ 不断笔。 */
export function appendFreehandPoint(flat: number[], x: number, y: number, maxPoints = MAX_FREEHAND_POINTS): number[] {
  if (!isFiniteNumber(x) || !isFiniteNumber(y)) return flat;
  if (flat.length >= 2) {
    const lastX = flat[flat.length - 2];
    const lastY = flat[flat.length - 1];
    if (Math.abs(x - lastX) < FREEHAND_MIN_STEP && Math.abs(y - lastY) < FREEHAND_MIN_STEP) return flat;
  }
  const next = [...flat, x, y];
  return next.length / 2 > maxPoints ? thinPoints(next, maxPoints) : next;
}

/** 一串点的包围盒（未取整、未 clamp）。 */
export function boundsOfPoints(flat: number[]): WarehouseRectGeometry | null {
  const pairs = toPointPairs(flat);
  if (!pairs.length) return null;
  const xs = pairs.map(([x]) => x);
  const ys = pairs.map(([, y]) => y);
  const minX = Math.min(...xs);
  const minY = Math.min(...ys);
  return { x: minX, y: minY, width: Math.max(...xs) - minX, height: Math.max(...ys) - minY };
}

/**
 * 折线/自由笔 → 标注。点存成**相对包围盒**的偏移，整体拖动只改 x/y 一处。
 *
 * 包围盒被 clamp 到非负时，整笔跟着平移（不是变形）—— 与货架 onRackDrag
 * 里 `Math.max(0, ...)` 同口径。
 */
export function annotationFromPolyline(
  id: string,
  kind: 'polygon' | 'freehand',
  absolutePoints: number[],
  style: Partial<WarehouseAnnotation> = {},
): WarehouseAnnotation | null {
  const bounds = boundsOfPoints(absolutePoints);
  const pairs = toPointPairs(absolutePoints);
  if (!bounds || pairs.length < 2) return null;
  const rect = clampRect(bounds, minSizeForKind(kind));
  return {
    ...style,
    id,
    kind,
    ...rect,
    points: pairs.flatMap(([x, y]) => [Math.round(x - bounds.x), Math.round(y - bounds.y)]),
  };
}

/** 折线/自由笔的 SVG path，坐标已换算成绝对画布坐标。 */
export function annotationPathData(annotation: WarehouseAnnotation): string {
  const pairs = toPointPairs(annotation.points ?? []);
  if (pairs.length < 2) return '';
  const commands = pairs.map(([px, py], index) =>
    `${index === 0 ? 'M' : 'L'}${Math.round(annotation.x + px)} ${Math.round(annotation.y + py)}`);
  if (annotation.kind === 'polygon') commands.push('Z');
  return commands.join(' ');
}

/**
 * 单条标注归一化。画不出来的整条丢掉（NaN 几何、点数不足的折线、空文字），
 * ⛔ 不做「补个默认值让它显示出来」—— 那会在图上凭空长出用户没画过的东西。
 */
export function normalizeAnnotation(value: unknown): WarehouseAnnotation | null {
  if (!value || typeof value !== 'object') return null;
  const input = value as Partial<WarehouseAnnotation>;
  if (typeof input.id !== 'string' || !input.id) return null;
  if (!ANNOTATION_KINDS.includes(input.kind as WarehouseAnnotationKind)) return null;
  if (!hasFiniteRect(input)) return null;
  const kind = input.kind as WarehouseAnnotationKind;
  const annotation: WarehouseAnnotation = { id: input.id, kind, ...clampRect(input as WarehouseRectGeometry, minSizeForKind(kind)) };
  if (kind === 'polygon' || kind === 'freehand') {
    const points = thinPoints(Array.isArray(input.points) ? input.points : []);
    if (toPointPairs(points).length < 2) return null;
    annotation.points = points;
  }
  if (typeof input.text === 'string' && input.text.trim()) annotation.text = input.text;
  if (kind === 'text' && !annotation.text) return null;
  if (typeof input.color === 'string') annotation.color = input.color;
  if (typeof input.fill === 'string') annotation.fill = input.fill;
  if (isFiniteNumber(input.strokeWidth)) annotation.strokeWidth = input.strokeWidth;
  if (isFiniteNumber(input.fontSize)) annotation.fontSize = input.fontSize;
  return annotation;
}

export function normalizeAnnotations(value: unknown): WarehouseAnnotation[] {
  if (!Array.isArray(value)) return [];
  const result: WarehouseAnnotation[] = [];
  for (const item of value) {
    const annotation = normalizeAnnotation(item);
    if (annotation) result.push(annotation);
    if (result.length >= MAX_ANNOTATIONS_PER_FLOOR) break;
  }
  return result;
}

/** 区域几何归一化。zoneId 必须是非空字符串 —— 没有它这块面积不指向任何区域。 */
export function normalizeZoneShapes(value: unknown): WarehouseZoneShape[] {
  if (!Array.isArray(value)) return [];
  const result: WarehouseZoneShape[] = [];
  const seen = new Set<string>();
  for (const item of value) {
    if (!item || typeof item !== 'object') continue;
    const shape = item as Partial<WarehouseZoneShape>;
    if (typeof shape.zoneId !== 'string' || !shape.zoneId || !hasFiniteRect(shape)) continue;
    // 同一层同一个区域只该有一块面积；重复时保留第一块。
    if (seen.has(shape.zoneId)) continue;
    seen.add(shape.zoneId);
    result.push({ zoneId: shape.zoneId, ...clampRect(shape as WarehouseRectGeometry) });
  }
  return result;
}

export function floorZoneShapes(floor?: WarehouseFloor | null): WarehouseZoneShape[] {
  return floor?.zoneShapes ?? [];
}

/** 仅在货架中心明确落入另一块区域、且不在归属区域内时提示复核。 */
export function rackZoneMismatch(rack: WarehouseRack, floor: WarehouseFloor): string | null {
  const centerX = rack.x + rack.width / 2;
  const centerY = rack.y + rack.height / 2;
  const containing = floorZoneShapes(floor).filter((shape) =>
    centerX >= shape.x && centerX <= shape.x + shape.width
    && centerY >= shape.y && centerY <= shape.y + shape.height);
  if (containing.some((shape) => shape.zoneId === rack.zoneId)) return null;
  return containing.length === 1 ? containing[0].zoneId : null;
}

export function floorAnnotations(floor?: WarehouseFloor | null): WarehouseAnnotation[] {
  return floor?.annotations ?? [];
}

export function zoneShapeOf(floor: WarehouseFloor | null | undefined, zoneId: string): WarehouseZoneShape | undefined {
  return floorZoneShapes(floor).find((shape) => shape.zoneId === zoneId);
}

/** upsert：同一层同一个区域只有一块面积，再画一次是**改**不是**加**。 */
export function upsertZoneShape(shapes: WarehouseZoneShape[], shape: WarehouseZoneShape): WarehouseZoneShape[] {
  const index = shapes.findIndex((item) => item.zoneId === shape.zoneId);
  if (index < 0) return [...shapes, shape];
  const next = [...shapes];
  next[index] = shape;
  return next;
}

/** 「一键放到图上」的默认位置：三列排开，不重叠。 */
export function defaultZoneShapeFor(zoneId: string, index: number): WarehouseZoneShape {
  return {
    zoneId,
    x: 40 + (index % 3) * 270,
    y: 40 + Math.floor(index / 3) * 210,
    width: 240,
    height: 180,
  };
}

/** 本层还没画上去的区域 —— 「哪些区域在这层图上还没位置」。 */
export function zonesMissingShapeOnFloor(zones: WarehouseZone[], floor?: WarehouseFloor | null): WarehouseZone[] {
  const placed = new Set(floorZoneShapes(floor).map((shape) => shape.zoneId));
  return zones.filter((zone) => !placed.has(zone.id));
}

export function nextLocationCode(zone: WarehouseZone, locations: WarehouseLocation[]): string {
  const used = new Set(locations.filter((location) => location.zoneId === zone.id).map((item) => item.code));
  let index = 1;
  while (used.has(`${zone.prefix}${String(index).padStart(2, '0')}`)) index += 1;
  return `${zone.prefix}${String(index).padStart(2, '0')}`;
}

export function daysInStorage(date: string | undefined, now = Date.now()): number | null {
  if (!date) return null;
  const parsed = new Date(date).getTime();
  if (Number.isNaN(parsed)) return null;
  return Math.max(0, Math.floor((now - parsed) / 86_400_000));
}

export function normalizedLocationPrefix(code: string): string {
  return code.match(/^[A-Za-z]+/)?.[0].toUpperCase() ?? '';
}

/**
 * 库存数量展示格式化。
 *
 * 2026-08-25 生产实测: 现场盘点页把 `168.96999999999997`、`29.990000000000002`
 * 直接打给仓管 —— 那是 IEEE754 浮点误差, 不是真实精度, 而且撑破了卡片宽度
 * (scrollWidth 229 > clientWidth 227)。
 *
 * 这里只做「展示层」收敛: 最多 3 位小数并去掉尾随 0。⛔ 不改任何参与计算的值。
 */
export function formatQuantity(value: number): string {
  if (!Number.isFinite(value)) return '—';
  const rounded = Math.round(value * 1000) / 1000;
  return String(rounded);
}

/**
 * 一组批次里「最长在库天数」。
 *
 * ⛔ 全部批次都缺入库日期时返回 null(展示为 —), 不能回落成 0 ——
 * 「0 天」等于向仓管断言这批货今天入库, 那是编造的读数 (核心原则#1 禁止假数据)。
 * 只要有一条批次有日期, 就按有日期的那些算最大值。
 */
export function maxDaysInStorage(dates: Array<string | undefined>, now = Date.now()): number | null {
  const known = dates.map((date) => daysInStorage(date, now)).filter((age): age is number => age !== null);
  return known.length ? Math.max(...known) : null;
}

/**
 * 尾沿防抖调度器 —— 把一串连续调用合并成最后一次之后执行一次。
 *
 * 用在拖拽货架上: `persist()` PUT 的是**整份** layout(含楼层的 base64 底图),
 * 每拖停一次就整份重传一遍; 底图几 MB 时连续微调会打出一串大请求。
 *
 * 🔴 为什么抽出来: 2026-08-26 想验「600ms 内连续微调只发一次」时发现,
 * 浏览器自动化**驱动不了** Vue Flow 的拖拽(它吃 d3-drag 的内部 pointer 处理,
 * 合成 PointerEvent 打不进去), 试了三次, 节点纹丝不动 —— 那不是「防抖没生效」,
 * 是**仪器够不着被测行为**。与其继续在浏览器里试, 不如把这段逻辑变成
 * 可以用假定时器确定性验证的纯函数。
 *
 * ⚠️ 它验的是「调度行为」, 不是「Vue Flow 拖拽会调用它」——
 * 后者由 index.vue 里 onRackDrag 的源码闸钉住。两层各管一段, 不要混为一谈。
 */
export function createTrailingDebouncer(waitMs: number) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  return {
    schedule(run: () => void) {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        timer = undefined;
        run();
      }, waitMs);
    },
    /** 组件卸载时调用, 免得已经离开页面还打一次请求。 */
    cancel() {
      if (timer) clearTimeout(timer);
      timer = undefined;
    },
    get pending() {
      return timer !== undefined;
    },
  };
}

/**
 * 画布选中标记 `zone:<zoneId>` / `annotation:<id>` 的解析。
 * ⛔ 按**第一个**冒号切，id 里再有冒号也不会被截断。
 */
export function parseMapSelection(value: string): { kind: 'zone' | 'annotation' | ''; id: string } {
  const separator = typeof value === 'string' ? value.indexOf(':') : -1;
  if (separator < 0) return { kind: '', id: '' };
  const kind = value.slice(0, separator);
  const id = value.slice(separator + 1);
  if ((kind === 'zone' || kind === 'annotation') && id) return { kind, id };
  return { kind: '', id: '' };
}

/** AI 识别底图时约定的画布尺寸 —— 模型返回的是 0~100 的百分比。 */
export const RECOGNIZED_CANVAS: WarehouseRectGeometry = { x: 0, y: 0, width: 1000, height: 700 };

/**
 * 把 AI 识别出来的「百分比区域框」换算成本层的区域几何。
 *
 * ⛔ 模型没给几何时返回 null，**不补一个默认框** —— 那等于在平面图上凭空
 * 画出一块图里根本没有的区域，而用户会以为那是识别出来的。
 */
export function zoneShapeFromPercent(
  zoneId: string,
  percent: { x?: number; y?: number; width?: number; height?: number },
  canvas: WarehouseRectGeometry = RECOGNIZED_CANVAS,
): WarehouseZoneShape | null {
  const { x, y, width, height } = percent;
  if (!isFiniteNumber(x) || !isFiniteNumber(y) || !isFiniteNumber(width) || !isFiniteNumber(height)) return null;
  if (width <= 0 || height <= 0) return null;
  return {
    zoneId,
    ...clampRect({
      x: (x / 100) * canvas.width,
      y: (y / 100) * canvas.height,
      width: (width / 100) * canvas.width,
      height: (height / 100) * canvas.height,
    }),
  };
}
