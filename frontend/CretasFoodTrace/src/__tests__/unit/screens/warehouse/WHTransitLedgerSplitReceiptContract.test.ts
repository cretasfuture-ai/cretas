import { readFileSync } from 'fs';
import { resolve } from 'path';

const source = readFileSync(
  resolve(__dirname, '../../../../screens/warehouse/inventory/WHTransitLedgerScreen.tsx'),
  'utf8',
);

describe('WHTransitLedger split receipt contract', () => {
  it('validates each submission as a delta against the remaining quantity', () => {
    expect(source).toContain('remainingReceiptQuantity(line)');
    expect(source).toContain('isValidReceiptDelta(line.receivedQuantity, output)');
    expect(source).not.toContain('value === Number(line.reportedQuantity)');
  });

  it('shows cumulative received and remaining quantities and clears stale line input', () => {
    expect(source).toContain('{COPY.receivedCumulative} {formatQty(line.receivedQuantity, line.unit)}');
    expect(source).toContain('{COPY.remaining} {formatQty(remaining, line.unit)}');
    expect(source).toContain('delete next[outputLineKey(item.id, line, index)]');
  });

  it('keeps one idempotency key for retries and clears it after a successful receipt', () => {
    expect(source).toContain('const idempotencyKeyById = useRef<Record<string, string>>({})');
    expect(source).toContain('idempotencyKeyById.current[item.id]');
    expect(source).toContain('createTransitReceiptIdempotencyKey(item.id)');
    expect(source).toContain('delete idempotencyKeyById.current[item.id]');
    expect(source).toContain('}, idempotencyKey)');
  });
});
