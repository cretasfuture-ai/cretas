import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import SalesPackagingSelect from '../components/SalesPackagingSelect.vue';
import { packagingSpecIdForUnit, salesPackagingOptions, salesPackagingSelectionError } from '../salesOrderNewLineContract';

const specs = [
  { id: 'ten', name: '默认箱规', packageUnit: '箱', baseUnit: '个', conversionFactor: 10, defaultSpec: true },
  { id: 'sixty', name: '箱规2', packageUnit: '箱', baseUnit: '个', conversionFactor: 60 },
  { id: 'retired', packageUnit: '箱', baseUnit: '个', conversionFactor: 2, active: false },
];
const product = { unit: '个', packagingSpecs: specs };

describe('explicit sales packaging selection', () => {
  it('requires a choice despite a default when two active specs use the same outer unit', () => {
    expect(salesPackagingSelectionError(product, '箱')).toContain('明确选择');
    expect(salesPackagingOptions(product, '箱').map(s => s.id)).toEqual(['ten', 'sixty']);
  });
  it('uses the chosen nondefault factor identity in the payload', () => {
    expect(salesPackagingSelectionError(product, '箱', 'sixty')).toBeNull();
    expect(packagingSpecIdForUnit(product, '箱', 'sixty')).toBe('sixty');
    expect(packagingSpecIdForUnit(product, '箱', 'ten')).toBe('ten');
  });
  it.each(['retired', 'foreign'])('rejects stale or unknown selection %s without falling back to default', id => {
    expect(salesPackagingSelectionError(product, '箱', id)).toContain('已失效');
    expect(packagingSpecIdForUnit(product, '箱', id)).toBeUndefined();
  });
  it('rejects a spec after switching to the basic unit, preserving single-spec compatibility', () => {
    expect(salesPackagingSelectionError(product, '个', 'sixty')).toContain('单位不一致');
    expect(salesPackagingSelectionError(product, '个')).toBeNull();
    const single = { unit: '个', packagingSpecs: [specs[0]] };
    expect(salesPackagingSelectionError(single, '箱')).toBeNull();
    expect(packagingSpecIdForUnit(single, '箱')).toBe('ten');
  });
  it('mounts the real selector, exposes both quantities and emits the selected identity', () => {
    const wrapper = mount(SalesPackagingSelect, { props: { options: specs.slice(0, 2) }, global: { stubs: {
      ElSelect: { name: 'ElSelect', template: '<div><slot /></div>', emits: ['update:modelValue'] },
      ElOption: { template: '<span>{{ label }}</span>', props: ['label', 'value'] },
    } } });
    expect(wrapper.text()).toContain('1箱=10个');
    expect(wrapper.text()).toContain('1箱=60个');
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'sixty');
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['sixty']);
  });
});
