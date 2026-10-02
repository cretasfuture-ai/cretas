import { describe, expect, it } from 'vitest';
import { financeManagerMenu, menuConfig } from '../menuConfig';

describe('factory operating report menu entry', () => {
  it('finance reaches reports and retains the serialized adjustment entry', () => {
    const reports = financeManagerMenu.find(item => item.path === '/analytics/ai-reports');
    expect(reports).toBeDefined();
    expect(reports?.hideForFactoryTypes).not.toContain('FACTORY');
    expect(reports?.hideForFactoryTypes).toContain('RESTAURANT');
    const adjustments = financeManagerMenu.find(item => item.path === '/finance/adjustments');
    expect(adjustments).toBeDefined();
    expect(adjustments?.hideForFactoryTypes).toEqual(['RESTAURANT']);
  });
  it('factory super admin has a visible report entry while other hidden analytics remain hidden', () => {
    const reports = menuConfig.find(item => item.path === '/analytics/ai-reports');
    expect(reports?.roles).toContain('factory_super_admin');
    expect(reports?.hideForFactoryTypes).not.toContain('FACTORY');
    expect(reports?.hideForFactoryTypes).toContain('RESTAURANT');
    expect(menuConfig.find(item => item.path === '/smart-bi')?.hideForFactoryTypes).toContain('FACTORY');
  });
});
