import { canCreateProductionPlan } from '../../../utils/productionPlanPermissions';

describe('production plan mobile permissions', () => {
  it.each(['factory_super_admin', 'department_admin', 'production_manager', 'dispatcher'])(
    'keeps %s on the PC creation path',
    (role) => expect(canCreateProductionPlan(role, false)).toBe(false),
  );

  it('also blocks platform read-only users', () => {
    expect(canCreateProductionPlan('platform_admin', true)).toBe(false);
  });
});
