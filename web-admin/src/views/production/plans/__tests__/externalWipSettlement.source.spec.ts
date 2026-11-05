import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parse } from '@vue/compiler-sfc';
import { parse as parseTemplate, NodeTypes, type ElementNode, type TemplateChildNode } from '@vue/compiler-dom';

const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');

describe('clean settlement external WIP contract', () => {
  it('renders the actual semi-output input outside both clean and exception-only branches', () => {
    const template = parse(source).descriptor.template!.content;
    const matches: Array<{ node: ElementNode; ancestors: ElementNode[] }> = [];
    function visit(nodes: TemplateChildNode[], ancestors: ElementNode[] = []) {
      for (const node of nodes) {
        if (node.type !== NodeTypes.ELEMENT) continue;
        if (node.props.some(p => p.type === NodeTypes.ATTRIBUTE
          && p.name === 'data-testid' && p.value?.content === 'settlement-semi-output')) {
          matches.push({ node, ancestors });
        }
        visit(node.children, [...ancestors, node]);
      }
    }
    visit(parseTemplate(template).children);
    expect(matches).toHaveLength(1);
    const { node, ancestors } = matches[0]!;
    expect(ancestors.some(n => n.tag === 'el-form')).toBe(true);
    expect([...ancestors, node].flatMap(n => n.props).some(p =>
      p.type === NodeTypes.DIRECTIVE && ['if', 'else', 'else-if'].includes(p.name)
        && (p.name === 'else' || (p.exp?.type === NodeTypes.SIMPLE_EXPRESSION
          && p.exp.content.includes('settlementPrefillClean'))))).toBe(false);
    expect(node.loc.source).toContain('v-model="completeForm.semiFinishedOutputQuantity"');
  });
  it('keeps clean settlement on confirm=true when no manual WIP exists', () => {
    expect(source).toContain('settlementPrefillClean.value && !hasManualWip');
    expect(source).toContain('confirm: true');
  });

  it('switches to the manual payload when a WIP row is added', () => {
    expect(source).toContain('const hasManualWip = settlementPrefillClean.value');
    expect(source).toContain('completeForm.value.semiFinishedConsumptions.length > 0');
    expect(source).toContain('manualOverride: true');
    expect(source).not.toContain('confirm: false');
    expect(source).toContain('rawMaterialConsumptions: buildRawConsumptionPayload()');
    expect(source).toContain('laborSegments: completeForm.value.laborSegments.length > 0');
    expect(source).toContain('semiFinishedConsumptions: buildWipConsumptionPayload()');
  });

  it('sends the semi-finished quantity on the one-click path when the input has a value', () => {
    const clean = source.slice(source.indexOf('const payload = settlementPrefillClean.value && !hasManualWip'));
    const oneClick = clean.slice(0, clean.indexOf('// Exception override path'));
    expect(oneClick).toContain('actualSemiFinishedQuantity: completeSemiFinishedOutputQuantity.value');
    expect(oneClick).not.toContain('manualOverride');
  });

  it('requires a reason and production write access for a manual override', () => {
    expect(source).toContain('manualOverrideReason: settlementManualReason.value');
    expect(source).toContain("return settlementManualReason.value ? '' : '请选择人工补录原因'");
    expect(source).toContain('? !firstWipLineError.value && !settlementManualBlockedReason.value');
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
