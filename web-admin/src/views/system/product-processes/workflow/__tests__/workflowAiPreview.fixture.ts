import type { ProductProcessWorkflowDefinition } from '../types';

export const EMPTY_CANVAS = { schemaVersion: 1, productTypeId: 'PT-PIG-400', status: 'DRAFT', version: 1, nodes: [], edges: [] } as unknown as ProductProcessWorkflowDefinition;
export const LARGE_SPEC = {
  rawMaterials: ['猪肉'],
  steps: [{
    process: '切分', processCategory: '加工', responsibleWorkerName: '张师傅',
    outputs: [{ kind: 'SEMI_FINISHED', name: '肉块', unit: 'kg', byproduct: false }],
    inputs: ['冰'], seasonings: [{ name: '盐', dosagePerKgG: 5, subsequentPotRatio: 0 }],
    mystery: { literal: '<原样>', enabled: false },
  }, {
    process: '装盒', processCategory: 'PACKING', responsibleWorkerId: 1309,
    outputs: [{ kind: 'FINISHED_GOOD', name: '盒装肉', unit: '盒', byproduct: false },
      { kind: 'SEMI_FINISHED', name: '边角料', unit: 'g', byproduct: true }],
  }],
  unfamiliar: { zero: 0, nullable: null },
};
// Independently enumerated business differences; do not derive the oracle from the renderer.
export const LARGE_PATHS = [
  'rawMaterials[0]', 'steps[0].add', 'steps[0].process', 'steps[0].processCategory',
  'steps[0].responsibleWorkerName', 'steps[0].outputs[0].kind', 'steps[0].outputs[0].name',
  'steps[0].outputs[0].unit', 'steps[0].outputs[0].byproduct', 'steps[0].inputs[0]',
  'steps[0].seasonings[0].name', 'steps[0].seasonings[0].dosagePerKgG', 'steps[0].seasonings[0].subsequentPotRatio',
  'steps[0].mystery.literal', 'steps[0].mystery.enabled', 'steps[1].add', 'steps[1].process',
  'steps[1].processCategory', 'steps[1].responsibleWorkerId', 'steps[1].outputs[0].kind',
  'steps[1].outputs[0].name', 'steps[1].outputs[0].unit', 'steps[1].outputs[0].byproduct',
  'steps[1].outputs[1].kind', 'steps[1].outputs[1].name', 'steps[1].outputs[1].unit',
  'steps[1].outputs[1].byproduct', 'unfamiliar.zero', 'unfamiliar.nullable',
];

