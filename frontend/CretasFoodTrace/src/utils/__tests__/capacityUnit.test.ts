import { toCapacityUnit } from '../capacityUnit';

describe('toCapacityUnit', () => {
  it('maps the three units capacity can be configured in', () => {
    expect(toCapacityUnit('件')).toBe('PIECE');
    expect(toCapacityUnit('个')).toBe('PIECE');
    expect(toCapacityUnit('kg')).toBe('KG');
    expect(toCapacityUnit('KG')).toBe('KG');
    expect(toCapacityUnit(' 公斤 ')).toBe('KG');
    expect(toCapacityUnit('千克')).toBe('KG');
    expect(toCapacityUnit('批')).toBe('BATCH');
  });

  it('does not guess or convert units it cannot check against', () => {
    expect(toCapacityUnit('盒')).toBeNull();
    expect(toCapacityUnit('袋')).toBeNull();
    expect(toCapacityUnit('斤')).toBeNull();
    expect(toCapacityUnit('吨')).toBeNull();
  });

  it('never defaults a missing unit to kg', () => {
    expect(toCapacityUnit(undefined)).toBeNull();
    expect(toCapacityUnit(null)).toBeNull();
    expect(toCapacityUnit('')).toBeNull();
  });
});
