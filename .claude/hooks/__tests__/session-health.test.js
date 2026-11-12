#!/usr/bin/env node
// session-health.js 的 organizer 注入: 阴阳性对照, 跑真脚本 + 临时目录
'use strict';
const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const SCRIPT = path.join(__dirname, '..', 'session-health.js');
const REAL_SKILL = path.join(__dirname, '..', '..', 'skills', 'organizer', 'SKILL.md');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'session-health-'));
const home = path.join(tmp, 'home');
const store = path.join(tmp, 'store', 'acct', 'org');
fs.mkdirSync(home, { recursive: true });
fs.mkdirSync(store, { recursive: true });

function rec(cli, title) {
  fs.writeFileSync(path.join(store, `local_${cli}.json`), JSON.stringify({ sessionId: `local_${cli}`, cliSessionId: cli, title }));
}
function transcript(name, ctxTokens) {
  const p = path.join(tmp, name + '.jsonl');
  const line = JSON.stringify({ type: 'assistant', message: { usage: { input_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: ctxTokens } } });
  fs.appendFileSync(p, line + '\n'); // 真实 transcript 只追加
  return p;
}
function run(sid, tp, ev = 'PostToolUse', skill = REAL_SKILL) {
  const r = spawnSync('node', [SCRIPT], {
    input: JSON.stringify({ session_id: sid, transcript_path: tp, hook_event_name: ev }),
    env: { ...process.env, HOME: home, USERPROFILE: home, CRETAS_CCD_SESSION_STORE: path.join(tmp, 'store'), CRETAS_ORGANIZER_SKILL: skill },
    encoding: 'utf8',
  });
  assert.strictEqual(r.status, 0, r.stderr);
  if (!r.stdout) return null;
  const o = JSON.parse(r.stdout).hookSpecificOutput;
  return o;
}

rec('org-1', 'Organizer｜降本｜第 3 任｜11-13');
rec('exec-1', '规则重整执行：xxx');
rec('org-2', 'Organizer｜降本｜第 4 任｜11-13');
rec('org-3', 'Organizer｜降本｜第 5 任｜11-13');

// T1 阳性: organizer 低于软线, 第一次调用注入交班流程原文 (含 SKILL 里的关键句), 事件名跟随输入
const t1 = run('org-1', transcript('a', 100000));
assert(t1 && t1.hookEventName === 'PostToolUse', 'T1: organizer 首次应注入, 事件名 PostToolUse');
assert(t1.additionalContext.includes('交班流程') && t1.additionalContext.includes('get_session self'), 'T1: 应带交班流程原文');

// T2 只注入一次
assert.strictEqual(run('org-1', transcript('a', 100000)), null, 'T2: 第二次不再注入');

// T3 阴性: 非 organizer 低于软线, 什么都不说
assert.strictEqual(run('exec-1', transcript('b', 100000)), null, 'T3: 执行会话不注入');

// T4 执行会话过软线: 只有原来的软线提醒, 不带 organizer 流程
const t4 = run('exec-1', transcript('b', 310000), 'UserPromptSubmit');
assert(t4 && t4.additionalContext.includes('软线 300K') && !t4.additionalContext.includes('交班流程原文'), 'T4: 执行会话只拿普通提醒');

// T5 organizer 过硬线: 普通提醒 + 交班流程原文
run('org-2', transcript('c', 100000));
const t5 = run('org-2', transcript('c', 410000));
assert(t5 && t5.additionalContext.includes('硬线 400K') && t5.additionalContext.includes('交班流程原文'), 'T5: organizer 到线要带交班流程');

// T6 没量到: SKILL 读不到时明说, 不静默
const t6 = run('org-3', transcript('d', 100000), 'SessionStart', path.join(tmp, 'nope.md'));
assert(t6 && /没量到/.test(t6.additionalContext) && t6.hookEventName === 'SessionStart', 'T6: 截不到流程要说没量到');

fs.rmSync(tmp, { recursive: true, force: true });
console.log('session-health organizer: 6/6 passed');
