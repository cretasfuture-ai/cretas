'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { hasOnCanvasBomGuidance } = require('../scenarios/bom-canvas-readonly-shared');

test('recognizes current on-canvas BOM guidance and rejects retired jump-away copy', () => {
  assert.equal(hasOnCanvasBomGuidance('还没有生效 BOM —— 在下方辅料 / 包材 cell 上配置即可自动建首版草稿'), true);
  assert.equal(hasOnCanvasBomGuidance('请在辅料 / 包材 / 副产 cell 上配置'), true);
  assert.equal(hasOnCanvasBomGuidance('去 BOM 配置'), false);
  assert.equal(hasOnCanvasBomGuidance('在右侧配置 BOM'), false);
});
const { classifyCanvas, stateFromRows } = require('../scenarios/bom-canvas-readonly-shared');

const base = (overrides = {}) => ({
  processNodes: 1,
  versionStatusVisible: true,
  versionRows: 1,
  basicUnitVisible: true,
  packagingCells: 1,
  packSubtitles: 1,
  packagingUnitEvidence: true,
  status: { active: true, draft: false, noActive: false, mismatch: false },
  ...overrides,
});

test('classifies active, draft, no-active, and mismatch real carrier states', () => {
  assert.equal(classifyCanvas(base()).result, 'PASS');
  assert.equal(classifyCanvas(base({ status: { active: true, draft: true, noActive: false, mismatch: false } })).result, 'PASS');
  assert.equal(classifyCanvas(base({ status: { active: false, draft: false, noActive: true, mismatch: false } })).result, 'PASS');
  assert.equal(classifyCanvas(base({ status: { active: true, draft: false, noActive: false, mismatch: true } })).result, 'PASS');
  assert.equal(classifyCanvas(base({ status: { active: true, draft: false, noActive: true, mismatch: false, conflictingProductRows: 1 } })).result, 'CONFIRMED_DEFECT');
});

test('keeps mixed product rows valid and only flags same-product active/no-active conflict', () => {
  const mixed = stateFromRows(['A：BOM 生效 v3', 'B：还没有生效 BOM · 草稿 v1 未生效']);
  assert.equal(mixed.conflictingProductRows, 0);
  assert.equal(classifyCanvas(base({ status: mixed })).result, 'PASS');
  const conflict = stateFromRows(['A：BOM 生效 v3 · 还没有生效 BOM']);
  assert.equal(conflict.conflictingProductRows, 1);
  assert.equal(classifyCanvas(base({ status: conflict })).result, 'CONFIRMED_DEFECT');
});

test('missing formal status, unit, packaging role evidence, or product context is not PASS', () => {
  assert.equal(classifyCanvas(base({ versionStatusVisible: false })).result, 'UNVERIFIED');
  assert.equal(classifyCanvas(base({ versionRows: 0 })).result, 'UNVERIFIED');
  assert.equal(classifyCanvas(base({ basicUnitVisible: false })).result, 'UNVERIFIED');
  assert.equal(classifyCanvas(base({ packagingCells: 1, packSubtitles: 0 })).result, 'CONFIRMED_DEFECT');
  assert.equal(classifyCanvas(base({ packagingCells: 0 })).result, 'UNVERIFIED');
  assert.equal(classifyCanvas(base(), { selected: false }).result, 'UNVERIFIED');
});

test('no PROCESS host remains a data precondition and byproduct does not imply BOM packaging', () => {
  assert.equal(classifyCanvas(base({ processNodes: 0 })).result, 'UNVERIFIED');
  assert.equal(classifyCanvas(base({ byproductCells: 1, packagingCells: 0 })).result, 'UNVERIFIED');
});
