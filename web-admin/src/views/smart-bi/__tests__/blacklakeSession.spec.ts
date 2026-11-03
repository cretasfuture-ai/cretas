import { describe, it, expect } from 'vitest';
import { tokenActivationDelayMs } from '../blacklakeSession';
const token = (iat: unknown) => `header.${btoa(JSON.stringify({ iat }))}.signature`;
describe('local test token activation without server auth relaxation', () => {
  it('sends already-active tokens immediately', () => expect(tokenActivationDelayMs(token(1000), 1000100)).toBe(0));
  it('waits for a small future issuance time', () => expect(tokenActivationDelayMs(token(1001), 1000100)).toBe(925));
  it('rejects large clock skew', () => expect(() => tokenActivationDelayMs(token(1010), 1000000)).toThrow('5秒'));
  it.each([null, true, '1000', -1, 1.5])('rejects invalid issuance metadata %s', iat => expect(() => tokenActivationDelayMs(token(iat), 1000000)).toThrow());
});
