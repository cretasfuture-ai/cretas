import { Comment, Fragment, isVNode, type VNode } from 'vue';

export function slotNodes(nodes: VNode[]): VNode[] {
  return nodes.flatMap(node => {
    if (node.type === Fragment && Array.isArray(node.children)) return slotNodes(node.children.filter(isVNode));
    return node.type === Comment ? [] : [node];
  });
}

export function fieldValue(source: unknown, path: string): unknown {
  return path.replace(/\[(\d+)\]/g, '.$1').split('.').reduce<unknown>((value, key) =>
    value != null && typeof value === 'object' ? (value as Record<string, unknown>)[key] : undefined, source);
}

export function length(value: string | number | undefined): string | undefined {
  if (value === undefined) return undefined;
  return typeof value === 'number' || /^\d+$/.test(value) ? `${value}px` : value;
}
