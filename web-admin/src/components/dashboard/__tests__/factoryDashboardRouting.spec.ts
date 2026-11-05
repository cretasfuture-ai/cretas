import { describe, expect, it } from 'vitest';
import { getDashboardComponent } from '../index';

describe('factory dashboard routing', () => {
  it('routes a factory HR administrator to the factory HR dashboard', () => {
    expect(getDashboardComponent('hr_admin', 'FACTORY')).toBe('DashboardHR');
  });

  it('keeps factory super admins on the standard factory dashboard', () => {
    expect(getDashboardComponent('factory_super_admin', 'FACTORY')).not.toBe('DashboardRestaurant');
  });
});
