#!/usr/bin/env node
// archive-guard.js 的阴阳性对照: 用临时会话记录目录跑真脚本
'use strict';
const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const SCRIPT = path.join(__dirname, '..', 'archive-guard.js');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'archive-guard-'));
const dir = path.join(root, 'acct', 'org');
fs.mkdirSync(dir, { recursive: true });

function rec(r) {
  fs.writeFileSync(path.join(dir, `${r.sessionId}.json`), JSON.stringify(r));
}
rec({ sessionId: 'local_old', cliSessionId: 'cli-old', title: '老 organizer' });
rec({ sessionId: 'local_exec', title: '执行线', spawnedFrom: { sessionId: 'local_old' },
  prs: [{ prNumber: 1663, state: 'OPEN' }, { prNumber: 1661, state: 'MERGED' }] });
rec({ sessionId: 'local_new', title: '新 organizer', spawnedFrom: { sessionId: 'local_old' }, lineageDetached: true });
rec({ sessionId: 'local_gone', title: '已归档子会话', spawnedFrom: { sessionId: 'local_old' }, isArchived: true });
rec({ sessionId: 'local_clean', cliSessionId: 'cli-clean', title: '没有子会话的' });
fs.writeFileSync(path.join(dir, 'local_broken.json'), '{"sessionId": "local_br');

function run(input, store = root) {
  const r = spawnSync('node', [SCRIPT], {
    input: JSON.stringify(input),
    env: { ...process.env, CRETAS_CCD_SESSION_STORE: store, CRETAS_ARCHIVE_GUARD_LOG: path.join(root, 'guard.log') },
    encoding: 'utf8',
  });
  assert.strictEqual(r.status, 0, `exit ${r.status}: ${r.stderr}`);
  return JSON.parse(r.stdout || '{}');
}
const denied = (o) => o.hookSpecificOutput && o.hookSpecificOutput.permissionDecision === 'deny';

// T1 阳性: 名下有未 detach 的执行线 -> 拒绝, 列出它和开着的 PR, 不列已 detach / 已归档的
const t1 = run({ session_id: 'cli-x', tool_input: { session_id: 'local_old' } });
assert(denied(t1), 'T1: 有挂着的子会话应拒绝');
const why = t1.hookSpecificOutput.permissionDecisionReason;
assert(why.includes('local_exec') && why.includes('#1663'), `T1: 应列出执行线和开着的 PR: ${why}`);
assert(!why.includes('#1661'), 'T1: 已合并的 PR 不列');
assert(!why.includes('local_new') && !why.includes('local_gone'), 'T1: 已 detach / 已归档的不列');

// T2 阴性: detach 之后放行 (把执行线改成已 detach)
rec({ sessionId: 'local_exec', title: '执行线', spawnedFrom: { sessionId: 'local_old' }, lineageDetached: true });
const t2 = run({ session_id: 'cli-x', tool_input: { session_id: 'local_old' } });
assert(!denied(t2) && !t2.systemMessage, `T2: 全部 detach 后应静默放行: ${JSON.stringify(t2)}`);

// T3 self 解析: 用 cli session id 找到自己; 没有子会话 -> 放行
const t3 = run({ session_id: 'cli-clean', tool_input: { session_id: 'self' } });
assert(!denied(t3) && !t3.systemMessage, 'T3: self 无子会话应放行');

// T4 self 解析到有挂着子会话的会话 -> 拒绝
rec({ sessionId: 'local_kid', title: '挂着的', spawnedFrom: { sessionId: 'local_clean' } });
const t4 = run({ session_id: 'cli-clean', tool_input: { session_id: 'self' } });
assert(denied(t4), 'T4: self 有挂着子会话应拒绝');

// T5 没量到: 记录目录不存在 / 目标不认识 -> 放行但必须发 systemMessage, 不能静默
const t5 = run({ session_id: 'x', tool_input: { session_id: 'local_old' } }, path.join(root, 'nope'));
assert(!denied(t5) && /没量到/.test(t5.systemMessage || ''), 'T5: 读不到记录要说没量到');
const t6 = run({ session_id: 'x', tool_input: { session_id: 'local_unknown' } });
assert(!denied(t6) && /没量到/.test(t6.systemMessage || ''), 'T6: 不认识的目标要说没量到');

fs.rmSync(root, { recursive: true, force: true });
console.log('archive-guard: 6/6 passed');
