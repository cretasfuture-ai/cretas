import { get, post, put } from './request';

export interface WarehouseZone {
  id: string;
  name: string;
  prefix: string;
  color?: string;
}

export interface WarehouseLocation {
  id: string;
  zoneId: string;
  code: string;
  name: string;
  notes?: string;
  rackId?: string;
  level?: number;
}

export interface WarehouseRack {
  id: string;
  zoneId: string;
  name: string;
  x: number;
  y: number;
  width: number;
  height: number;
  levels: number;
}

/** 平面图上的一个矩形，坐标系与货架的 x/y/width/height 完全一致（画布像素）。 */
export interface WarehouseRectGeometry {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * 【结构层】储存区域在**某一层**平面图上的几何。
 *
 * ⚠️ 为什么挂在 floor 上而不是 zone 上: 同一个区域可能只存在于某一层
 * (生产实测六膳门 LIUSHANMEN 有 3 层楼、6 个区域 —— 冷冻库/冷藏库/急冻库/
 * 常温库/缓化库/研发库, 它们并不是每层都有)。挂在 zone 上就只能表达
 * 「这个区域在仓库里的一个位置」, 表达不了「它在几层的哪一块」。
 *
 * 这一层是**数据**: zoneId 指向 layout.zones 里的真实区域, 可以被别处引用
 * (例如「这批货在哪个区」)。⛔ 自由涂画不许进这里, 见 WarehouseAnnotation。
 */
export interface WarehouseZoneShape extends WarehouseRectGeometry {
  zoneId: string;
}

export type WarehouseAnnotationKind = 'rect' | 'ellipse' | 'polygon' | 'freehand' | 'text';

/**
 * 【标注层】自由图形与文字标注 —— 纯展示。
 *
 * ⛔ 不参与任何业务查询: 没有 zoneId、没有编码、不会被批次/储位引用。
 * 这条边界是 owner 拍板的两层分工: 结构层(zones/racks 几何)是数据,
 * 标注层是画在图上的说明。把自由图形混进 zones 会让「这批货在哪个区」
 * 这类查询开始命中一个用户随手画的圈。
 *
 * 坐标语义: x/y/width/height 是**包围盒**(与 racks 同坐标系);
 * polygon / freehand 的 points 是**相对包围盒原点**的扁平数组
 * [x0,y0,x1,y1,...] —— 相对存储让「整体拖动」只改 x/y 一处, 不用遍历点。
 */
export interface WarehouseAnnotation extends WarehouseRectGeometry {
  id: string;
  kind: WarehouseAnnotationKind;
  /** polygon / freehand 的折线点，相对 x/y 的扁平数组 [x0,y0,x1,y1,...] */
  points?: number[];
  /** text 标注的文字；矩形/圆/多边形也可带一句题注 */
  text?: string;
  color?: string;
  fill?: string;
  strokeWidth?: number;
  fontSize?: number;
}

export interface WarehouseFloor {
  id: string;
  name: string;
  racks: WarehouseRack[];
  /** 结构层：储存区域在本层的几何 */
  zoneShapes?: WarehouseZoneShape[];
  /** 标注层：自由图形与文字，纯展示 */
  annotations?: WarehouseAnnotation[];
  background?: string;
}

export interface WarehouseLayout {
  zones: WarehouseZone[];
  locations: WarehouseLocation[];
  floors: WarehouseFloor[];
}

export interface RecognizedWarehouseLayout {
  zones?: Array<WarehouseZone & { x?: number; y?: number; width?: number; height?: number }>;
  racks?: WarehouseRack[];
  confidence?: number;
}

export function getWarehouseLayout(factoryId: string) {
  return get<WarehouseLayout>(`/${factoryId}/warehouse/layout`);
}

export function saveWarehouseLayout(factoryId: string, layout: WarehouseLayout) {
  return put<WarehouseLayout>(`/${factoryId}/warehouse/layout`, layout);
}

export function recognizeWarehouseLayout(factoryId: string, imageBase64: string, contentType: string) {
  return post<RecognizedWarehouseLayout>(`/${factoryId}/warehouse/layout/recognize`, {
    imageBase64,
    contentType,
  });
}
