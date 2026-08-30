import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');

describe('clean settlement external WIP contract', () => {
  it('keeps clean settlement on confirm=true when no manual WIP exists', () => {
    expect(source).toContain('settlementPrefillClean.value && !hasManualWip');
    expect(source).toContain('confirm: true');
  });

  it('switches to the manual payload when a WIP row is added', () => {
    expect(source).toContain('const hasManualWip = settlementPrefillClean.value');
    expect(source).toContain('completeForm.value.semiFinishedConsumptions.length > 0');
    expect(source).toContain('confirm: false');
    expect(source).toContain('rawMaterialConsumptions: buildRawConsumptionPayload()');
    expect(source).toContain('laborSegments: completeForm.value.laborSegments.length > 0');
    expect(source).toContain('semiFinishedConsumptions: buildWipConsumptionPayload()');
  });

  it('exposes a progressive WIP entry and loads current-factory inventory', () => {
    expect(source).toContain('补充外部半成品领用');
    expect(source).toContain('@click="openSettlementManualWip"');
    expect(source).toContain('listAvailableWip(factoryId.value)');
    expect(source).toContain('当前可用');
  });

  it('blocks empty, sub-minimum, and over-available WIP quantities', () => {
    expect(source).toContain('半成品领用数量必须至少为 0.01');
    expect(source).toContain(':min="0.01"');
    expect(source).toContain('半成品领用超出可用量');
    expect(source).toContain('!firstWipLineError.value');
  });
});
