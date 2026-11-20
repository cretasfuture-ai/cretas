'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..', '..', '..', '..');
const cliEntry = /cli-runner\.mjs --production-readonly/;

test('canonical skill and compatibility pointer both route production to the shared harness', () => {
  const canonicalPath = path.join(repoRoot, '.agents', 'skills', 'e2e-web-admin', 'SKILL.md');
  const pointerPath = path.join(repoRoot, '.claude', 'skills', 'e2e-web-admin', 'SKILL.md');
  const canonical = fs.readFileSync(canonicalPath, 'utf8');
  const pointer = fs.readFileSync(pointerPath, 'utf8');
  assert.match(canonical, cliEntry);
  assert.match(canonical, /scripts\/e2e\/production-readonly\/mcp-entry\.js/);
  assert.match(canonical, /actualBusinessWrites.*0/);
  assert.match(canonical, /F006 Production-Write Exception/);
  assert.match(canonical, /factoryUser\.factoryId === 'F006'/);
  assert.match(canonical, /standing-authorization-and-visual-acceptance\.md/);
  assert.doesNotMatch(canonical, /every non-F006 tenant/);
  assert.match(canonical, /read-only run remains zero-write/);
  assert.match(pointer, /CANONICAL: \.agents\/skills\/e2e-web-admin\/SKILL\.md/);
  assert.match(pointer, /scripts\/e2e\/production-readonly/);
  assert.doesNotMatch(pointer, /chromium\.launch|CRUD|create\/edit\/delete/i);
});

test('project Playwright skill routes direct MCP production runs to the canonical guarded entry', () => {
  const skillPath = path.join(repoRoot, '.agents', 'skills', 'project-playwright-e2e', 'SKILL.md');
  const skill = fs.readFileSync(skillPath, 'utf8');
  assert.match(skill, cliEntry);
  assert.match(skill, /browser_run_code_unsafe/);
  assert.match(skill, /scripts\/e2e\/production-readonly\/mcp-entry\.js/);
  assert.match(skill, /before-send mutation guard/);
  assert.match(skill, /Production read-only acceptance business writes must be zero/);
  assert.match(skill, /F006 Production-Write Exception/);
  assert.match(skill, /factoryUser\.factoryId === 'F006'/);
  assert.match(skill, /read-only run remains zero-write for F006/);
  assert.match(skill, /nonprod-business-flow-audit\.mjs/);
  assert.doesNotMatch(skill, /Prefer standalone Node scripts over persistent browser-profile MCP sessions/);
  assert.doesNotMatch(skill, /1\. Read `\.mcp\.json`/);
});

test('repository AGENTS policy pins both guarded production Web entries', () => {
  const agents = fs.readFileSync(path.join(repoRoot, 'AGENTS.md'), 'utf8');
  const gate = agents.match(/^9\. \*\*生产 Web 只读验收入口\*\*[^\r\n]*/m)?.[0] ?? '';
  assert.match(gate, cliEntry);
  assert.match(gate, /filename.*scripts\/e2e\/production-readonly\/mcp-entry\.js/);
  assert.match(gate, /before-send mutation guard 与证据 schema/);
  assert.match(gate, /actualBusinessWrites == 0/);
  assert.match(gate, /blocked mutation attempts 为 0/);
  assert.match(gate, /nonprod-business-flow-audit\.mjs/);
  assert.match(agents, /F006 生产业务写入特例/);
  assert.match(agents, /factoryUser\.factoryId == 'F006'/);
  assert.match(agents, /非 F006 租户按常设授权/);
  assert.doesNotMatch(agents, /其他所有租户在生产环境的业务写入必须为 0/);
});

test('production read-only README keeps the F006 write exception outside the guarded harness', () => {
  const readme = fs.readFileSync(path.join(repoRoot, 'scripts', 'e2e', 'production-readonly', 'README.md'), 'utf8');
  assert.match(readme, cliEntry);
  assert.match(readme, /F006 production-write exception does not apply/);
  assert.match(readme, /authenticated tenant is F006/);
  assert.match(readme, /remains strictly zero-write/);
  assert.match(readme, /never add its business mutations/);
});
