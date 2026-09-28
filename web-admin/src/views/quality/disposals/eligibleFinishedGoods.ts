/** A missing balance must never fall back to produced quantity, which includes shipped stock. */
export function disposalAvailableQuantity(batch: { availableQuantity?: unknown }): number | null {
  const value = batch.availableQuantity;
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

export function isEligibleFinishedGoods(batch: { availableQuantity?: unknown }): boolean {
  const available = disposalAvailableQuantity(batch);
  return available !== null && available > 0;
}
