import { describe, expect, it } from 'vitest';
import { comparableCostGroups } from '../blacklakeComparison';

describe('same product cost comparisons', () => {
  it('excludes mixed names and mismatched aggregate coverage while retaining valid units', () => {
    const groups = [
      {productCode:'A',product:'半成品',unit:'KG',eligibleProjects:2},
      {productCode:'B',product:'成品',unit:'包',eligibleProjects:2},
      {productCode:'B',product:'成品',unit:'盒',eligibleProjects:2},
    ];
    const rows = [
      {productCode:'A',product:'半成品',unit:'KG',unitCostAvailable:true},
      {productCode:'A',product:'缓化料',unit:'KG',unitCostAvailable:true},
      {productCode:'B',product:'成品',unit:'包',unitCostAvailable:true},
      {productCode:'B',product:'成品',unit:'包',unitCostAvailable:true},
      {productCode:'B',product:'成品',unit:'盒',unitCostAvailable:true},
    ];
    const before=JSON.stringify({groups,rows});
    expect(comparableCostGroups(groups,rows)).toEqual([groups[1]]);
    expect(comparableCostGroups(groups,[])).toEqual([]);
    expect(JSON.stringify({groups,rows})).toBe(before);
  });
});
