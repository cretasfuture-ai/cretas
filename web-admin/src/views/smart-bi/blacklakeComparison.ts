import type { CostComparison, CostProject } from '@/api/smartbi/blacklake';

/** Reject legacy aggregates which mix product names/stages under one source code. */
export function comparableCostGroups<T extends Pick<CostComparison, 'productCode' | 'product' | 'unit' | 'eligibleProjects'>>(
  groups: readonly T[],
  projects: readonly Pick<CostProject, 'productCode' | 'product' | 'unit' | 'unitCostAvailable'>[],
): T[] {
  return groups.filter(group => {
    if (!group.productCode || !group.unit) return false;
    const rows = projects.filter(p => p.unitCostAvailable && p.productCode === group.productCode && p.unit === group.unit);
    return rows.length > 0 && rows.length === group.eligibleProjects && rows.every(p => p.product === group.product);
  });
}
