/**
 * 紧急插单止血 (2026-10-24): 界面只有在后端明确 FEASIBLE 时才显示「可行」。
 * 没拿到分析 / 旧后端缺字段 / 有资源没核对 —— 一律「未验证」, 不得默认可行。
 */
import {
  resolveUrgentInsertFeasibility,
  UrgentInsertImpactAnalysis,
} from '../../types/dispatcher';

function impact(extra: Partial<UrgentInsertImpactAnalysis>): UrgentInsertImpactAnalysis {
  return {
    slotId: 'S-1',
    impactLevel: 'none',
    impactScore: 0,
    chainImpact: {} as UrgentInsertImpactAnalysis['chainImpact'],
    affectedPlans: [],
    resourceImpact: {},
    recommendation: '',
    requiresApproval: false,
    ...extra,
  };
}

describe('resolveUrgentInsertFeasibility', () => {
  it('no analysis at all is UNVERIFIED', () => {
    expect(resolveUrgentInsertFeasibility(null)).toBe('UNVERIFIED');
    expect(resolveUrgentInsertFeasibility(undefined)).toBe('UNVERIFIED');
  });

  it('old backend without the new fields is UNVERIFIED, not feasible', () => {
    expect(resolveUrgentInsertFeasibility(impact({}))).toBe('UNVERIFIED');
  });

  it('isFeasible=true without FEASIBLE status is still UNVERIFIED', () => {
    expect(resolveUrgentInsertFeasibility(impact({ isFeasible: true }))).toBe('UNVERIFIED');
  });

  it('backend UNVERIFIED stays UNVERIFIED', () => {
    expect(resolveUrgentInsertFeasibility(impact({
      isFeasible: false,
      feasibilityStatus: 'UNVERIFIED',
      unverifiedReasons: ['设备未验证: …'],
    }))).toBe('UNVERIFIED');
  });

  it('verified shortage is INFEASIBLE', () => {
    expect(resolveUrgentInsertFeasibility(impact({ isFeasible: false, feasibilityStatus: 'INFEASIBLE' })))
      .toBe('INFEASIBLE');
  });

  it('only explicit FEASIBLE + isFeasible=true is FEASIBLE', () => {
    expect(resolveUrgentInsertFeasibility(impact({ isFeasible: true, feasibilityStatus: 'FEASIBLE' })))
      .toBe('FEASIBLE');
  });
});
