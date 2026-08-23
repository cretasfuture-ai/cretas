import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(
  process.cwd(),
  'src/views/platform/label-qc-diagnostics/index.vue',
), 'utf8');

describe('label QC platform diagnostics workbench', () => {
  it('explains the customer/platform boundary and isolates the current problem box', () => {
    expect(source).toContain('平台专用 · 六扇门 · 不进入工厂审核量');
    expect(source).toContain('其他框已隐藏');
    expect(source).toContain('full-canvas');
    expect(source).toContain('crop-canvas');
    expect(source).toContain('problem-box');
  });

  it('loads the Liushanmen queue for platform accounts without a factory id', () => {
    expect(source).toContain("const DEFAULT_PLATFORM_FACTORY_ID = 'LIUSHANMEN'");
    expect(source).toContain('auth.factoryId || DEFAULT_PLATFORM_FACTORY_ID');
    expect(source).toContain('listLabelQcPlatformCandidates(targetFactoryId.value');
    expect(source).toContain('reviewLabelQcPlatformCandidate(targetFactoryId.value');
    expect(source).toContain('!candidates.value.length && total.value > 0');
    expect(source).toContain('await load()');
  });

  it('supports one-action decisions and keyboard shortcuts', () => {
    expect(source).toContain("'1': ['NO_DEFECT'");
    expect(source).toContain("'2': ['UNJUDGEABLE'");
    expect(source).toContain("'3': ['MISSING_WHITE_LABEL'");
    expect(source).toContain("'4': ['MISSING_COLOR_LABEL'");
    expect(source).toContain('await saveCurrent(true)');
  });

  it('renders solid high-contrast problem boxes', () => {
    expect(source).toContain('border: 4px solid #e31b0c');
    expect(source).not.toContain('border-style: dashed');
  });
});
