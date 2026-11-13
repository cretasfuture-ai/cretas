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

// 过硬线后的交接回读
const hdir = path.join(home, '.claude', 'context-handoff', 'handoff');
fs.mkdirSync(hdir, { recursive: true });
const stateOf = (sid) => JSON.parse(fs.readFileSync(path.join(home, '.claude', 'context-handoff', 'health', sid + '.json'), 'utf8'));
rec('exec-2', '执行线 2');
rec('exec-3', '执行线 3');
rec('exec-4', '执行线 4');

// T7 阳性: 过硬线后又涨 35K, handoff/ 里只有硬线前的旧文件 (阴性干扰) -> 回读提醒一次, 再涨不重复
const old = path.join(hdir, 'old-batch.md');
fs.writeFileSync(old, 'x');
const past = new Date(Date.now() - 3600 * 1000);
fs.utimesSync(old, past, past);
run('exec-2', transcript('e', 100000));
const t7a = run('exec-2', transcript('e', 410000));
assert(t7a && t7a.additionalContext.includes('硬线 400K'), 'T7: 先拿到硬线提醒');
assert.strictEqual(run('exec-2', transcript('e', 420000)), null, 'T7: 涨不到 30K 不回读提醒');
const t7b = run('exec-2', transcript('e', 445000));
assert(t7b && t7b.additionalContext.includes('还没看到交接文件'), 'T7: 涨 35K 无新交接应回读提醒');
assert.strictEqual(run('exec-2', transcript('e', 470000)), null, 'T7: 回读提醒只说一次');

// T8 阴性: 硬线后写了交接 (按批次名, 不是 sid 名) -> 不提醒, 状态记 handoffSeenAt
run('exec-3', transcript('f', 100000));
run('exec-3', transcript('f', 410000));
fs.writeFileSync(path.join(hdir, '20261113-some-batch.md'), 'handoff');
assert.strictEqual(run('exec-3', transcript('f', 445000)), null, 'T8: 已写交接 (任意名) 不提醒');
assert(stateOf('exec-3').handoffSeenAt > 0, 'T8: 状态里应记下看到交接的时间');

// T9 变异对照: 交接文件 mtime 早于硬线 -> 不算写了
run('exec-4', transcript('g', 100000));
run('exec-4', transcript('g', 410000));
const f9 = path.join(hdir, '20261113-some-batch.md');
fs.utimesSync(f9, past, past);
const t9 = run('exec-4', transcript('g', 445000));
assert(t9 && t9.additionalContext.includes('还没看到交接文件'), 'T9: 硬线之前的交接不算');

fs.rmSync(tmp, { recursive: true, force: true });
console.log('session-health: 9/9 passed');
