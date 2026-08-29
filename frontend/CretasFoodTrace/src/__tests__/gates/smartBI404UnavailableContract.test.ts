import fs from 'fs';
import path from 'path';

import { classifySmartBIError } from '../../services/api/smartbiError';

const SMARTBI_SCREENS = path.join(__dirname, '..', '..', 'screens', 'smartbi');

describe('SmartBI 404 is a not-launched state, not a retryable outage', () => {
  it('classifies an Axios-shaped 404 as unavailable', () => {
    expect(classifySmartBIError({
      isAxiosError: true,
      response: { status: 404 },
    })).toBe('unavailable');
  });

  it.each([500, 503])('keeps HTTP %s retryable', (status) => {
    expect(classifySmartBIError({
      isAxiosError: true,
      response: { status },
    })).toBe('retryable');
  });

  it('keeps network failures retryable', () => {
    expect(classifySmartBIError({ isAxiosError: true })).toBe('retryable');
  });

  it.each([
    ['SalesAnalysisScreen.tsx', '销售分析还没上线'],
    ['FinanceAnalysisScreen.tsx', '财务分析还没上线'],
    ['DynamicAnalysisScreen.tsx', '多表分析还没上线'],
  ])('%s renders the honest 404 wording through the shared classifier', (file, wording) => {
    const source = fs.readFileSync(path.join(SMARTBI_SCREENS, file), 'utf8');
    expect(source).toContain('classifySmartBIError(err)');
    expect(source).toContain("kind === 'unavailable'");
    expect(source).toContain(wording);
  });
});
