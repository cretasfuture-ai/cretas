/** Accept only complete calendar dates; never roll invalid days into the next month. */
export function parseOrderDeliveryDate(value: string): string | null {
  const text = value.trim();
  if (!text) return '';
  const match = /^(\d{4})(\d{2})(\d{2})$/.exec(text)
    || /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > days[month - 1]!) return null;
  return `${match[1]}-${match[2]}-${match[3]}`;
}
