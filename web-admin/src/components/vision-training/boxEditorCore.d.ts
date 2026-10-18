export type BoxCoordinates = [number, number, number, number];
export type BoxHit = { index: number; mode: 'new' | 'move' | 'resize'; corner: number };
export interface BoxHistory<T> { readonly length: number; push(boxes: T[]): void; pop(): T[] | null; clear(): void }
export function clamp(value: number): number;
export function corners(box: BoxCoordinates): Array<[number, number]>;
export function hitBox(boxes: BoxCoordinates[], x: number, y: number, width: number, height: number, tolerance?: number): BoxHit;
export function moveBox(box: BoxCoordinates, dx: number, dy: number): BoxCoordinates;
export function resizeBox(box: BoxCoordinates, corner: number, x: number, y: number): BoxCoordinates;
export function normaliseBox(box: BoxCoordinates, minimum?: number): BoxCoordinates | null;
export function zoomAnchor(scroll: number, local: number, size: number): number;
export function createBoxHistory<T>(limit?: number): BoxHistory<T>;
