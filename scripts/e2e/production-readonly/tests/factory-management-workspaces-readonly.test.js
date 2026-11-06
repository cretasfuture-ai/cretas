'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { describeHarness, resolveScenarios } = require('../core/run-suite');
const scenario = require('../scenarios/factory-management-workspaces-readonly');

test('registers the opt-in four-workspace read-only scenario', () => {
  assert.equal(scenario.id, 'factory-management-workspaces-readonly');
  assert.equal(scenario.defaultEnabled, false);
  assert.equal(scenario.WORKSPACE_TARGETS.length, 4);
  assert.deepEqual(scenario.WORKSPACE_TARGETS.map(item => item.title), ['经营总览', '异常中心', '方案与审批', '追溯与复盘']);
  assert.equal(new Set(scenario.WORKSPACE_TARGETS.map(item => item.path)).size, 4);
  assert.deepEqual(scenario.WORKSPACE_TARGETS.map(item => item.targetLandmark), ['今日生产概览', '缺料异常', '生产计划管理', '溯源查询']);
  assert.equal(describeHarness().scenarios.includes(scenario.id), true);
  assert.equal(describeHarness().defaultScenarios.includes(scenario.id), false);
  assert.equal(describeHarness().scenarios.includes(scenario.denied.id), true);
  assert.equal(describeHarness().defaultScenarios.includes(scenario.denied.id), false);
  assert.equal(resolveScenarios([scenario.id])[0], scenario);
  assert.equal(resolveScenarios([scenario.denied.id])[0], scenario.denied);
});

test('positive workspace evidence requires a ready page, visible target, and usable GET destination', () => {
  const expected = scenario.WORKSPACE_TARGETS[0];
  assert.equal(scenario.assertWorkspacePositive({
    path: expected.path,
    heading: expected.title,
    state: { visible: 'ready' },
    linkCount: 2,
    linkTitles: [expected.targetTitle, 'AI经营报告'],
    targetPath: expected.targetPath,
    targetLandmarkFound: true,
    targetErrorMarkers: [],
  }, expected), true);
});

test('a visible workspace link is the ready state, while the other terminal states stay distinct', () => {
  assert.equal(scenario.visibleState({ visible: 'link' }), 'ready');
  for (const visible of ['loading', 'error', 'unavailable', 'denied', 'empty', 'unknown']) {
    assert.equal(scenario.visibleState({ visible }), visible);
  }
});

test('loading, error, denied, unavailable, empty, and unknown targets cannot pass', () => {
  for (const visible of ['loading', 'error', 'denied', 'unavailable', 'empty']) {
    assert.equal(scenario.invalidState({ state: { visible }, linkCount: 0 }), true, visible);
  }
  assert.equal(scenario.invalidState({ state: { visible: 'ready' }, linkCount: 0 }), true);
  assert.equal(scenario.invalidState({ state: { visible: 'ready' }, linkCount: 1 }), false);
  assert.throws(() => scenario.assertWorkspacePositive({
    path: '/workspaces/unknown',
    heading: '未知工作区',
    state: { visible: 'ready' },
    linkCount: 1,
    linkTitles: ['首页驾驶舱'],
    targetPath: '/unknown',
    targetLandmarkFound: true,
    targetErrorMarkers: [],
  }, scenario.WORKSPACE_TARGETS[0]));
  assert.throws(() => scenario.assertWorkspacePositive({
    path: scenario.WORKSPACE_TARGETS[0].path,
    heading: scenario.WORKSPACE_TARGETS[0].title,
    state: { visible: 'ready' },
    linkCount: 1,
    linkTitles: [scenario.WORKSPACE_TARGETS[0].targetTitle],
    targetPath: scenario.WORKSPACE_TARGETS[0].targetPath,
    targetLandmarkFound: false,
    targetErrorMarkers: [],
  }, scenario.WORKSPACE_TARGETS[0]));
});

test('error detection uses semantic states, not ordinary numeric business values', () => {
  assert.equal(scenario.hasSemanticTargetError({ errorTestIds: [], headingTexts: ['今日产量 500 kg / 403 件'] }), false);
  assert.equal(scenario.hasSemanticTargetError({ errorTestIds: ['trace-error'], headingTexts: [] }), true);
  assert.equal(scenario.hasSemanticTargetError({ errorTestIds: [], headingTexts: ['权限不足'] }), true);
});

test('denied-role evidence requires hidden menu, all four denied routes, and no links', () => {
  const snapshot = {
    role: 'warehouse_manager',
    workspaceMenuVisible: false,
    routes: scenario.WORKSPACE_TARGETS.map(item => ({ path: item.path, finalPath: '/403', explicitDenied: false, denied: true, linkCount: 0, authenticatedSameFactory: true })),
  };
  assert.equal(scenario.assertDeniedSnapshot(snapshot), true);
  assert.throws(() => scenario.assertDeniedSnapshot({ ...snapshot, workspaceMenuVisible: true }));
  assert.throws(() => scenario.assertDeniedSnapshot({ ...snapshot, routes: snapshot.routes.map((route, index) => index ? route : { ...route, denied: false }) }));
  assert.throws(() => scenario.assertDeniedSnapshot({ ...snapshot, routes: snapshot.routes.map((route, index) => index ? route : { ...route, linkCount: 1 }) }));
  assert.throws(() => scenario.assertDeniedSnapshot({ ...snapshot, role: null }));
  assert.throws(() => scenario.assertDeniedSnapshot({ ...snapshot, role: 'factory_super_admin' }));
  for (const finalPath of ['/login', '/404', '/dashboard']) {
    assert.throws(() => scenario.assertDeniedSnapshot({ ...snapshot, routes: snapshot.routes.map(route => ({ ...route, finalPath })) }));
  }
  assert.throws(() => scenario.assertDeniedSnapshot({ ...snapshot, routes: snapshot.routes.map(route => ({ ...route, authenticatedSameFactory: false })) }));
});
