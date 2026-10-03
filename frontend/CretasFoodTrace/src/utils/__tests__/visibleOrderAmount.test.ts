import { formatVisibleOrderAmount } from '../visibleOrderAmount';

it.each([null, undefined, '', 'bad', Number.NaN])('does not invent zero for missing or invalid financial data: %s', value => {
  expect(formatVisibleOrderAmount(value)).toBe('金额未提供');
});

it.each([[0, '¥0'], ['0', '¥0'], [353.98, '¥353.98'], [12000, '¥12,000']])('preserves a real financial amount: %s', (value, expected) => {
  expect(formatVisibleOrderAmount(value)).toBe(expected);
});
