#!/usr/bin/env node
// scheduled-task-no-selfloop.js: 阴阳性对照, 跑真脚本 + 临时 transcript
'use strict';
const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const SCRIPT = path.join(__dirname, '..', 'scheduled-task-no-selfloop.js');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'no-selfloop-'));
function tr(name, lines) {
  const p = path.join(tmp, name + '.jsonl');
  fs.writeFileSync(p, lines.map((l) => JSON.stringify(l)).join('\n') + '\n');
  return p;
}
function run(tool, tp) {
  const r = spawnSync('node', [SCRIPT], { input: JSON.stringify({ tool_name: tool, transcript_path: tp, hook_event_name: 'PreToolUse' }), encoding: 'utf8' });
  assert.strictEqual(r.status, 0, r.stderr);
  return r.stdout ? JSON.parse(r.stdout).hookSpecificOutput : null;
}
const denied = (o) => !!o && o.permissionDecision === 'deny';

// 真实形状: 桌面应用定时任务首行是 queue-operation, content 以 <scheduled-task name= 开头 (11-13 8 小时检查 transcript 实测)
const sched = tr('sched', [
  { type: 'queue-operation', operation: 'enqueue', content: '<scheduled-task name="cretas-x" file="C:\\x\\SKILL.md">\nThis is an automated run' },
  { type: 'user', message: { content: 'hi' } },
]);
// 普通会话: 第一条 user 消息是人打的, 后面才粘贴过任务提示词 -> 不拦
const normal = tr('normal', [
  { type: 'user', message: { content: [{ type: 'text', text: '帮我看看定时任务' }] } },
  { type: 'user', message: { content: '<scheduled-task name="pasted">' } },
]);

assert(denied(run('ScheduleWakeup', sched)), 'T1: 定时任务会话 ScheduleWakeup 应拦');
assert(denied(run('CronCreate', sched)), 'T2: 定时任务会话 CronCreate 应拦');
assert(denied(run('mcp__scheduled-tasks__update_scheduled_task', sched)), 'T3: 改定时任务应拦');
assert.strictEqual(run('mcp__scheduled-tasks__list_task_runs', sched), null, 'T4: 读运行记录应放行');
assert.strictEqual(run('mcp__scheduled-tasks__list_scheduled_tasks', sched), null, 'T5: 列任务应放行');
assert.strictEqual(run('CronCreate', normal), null, 'T6: 普通会话 CronCreate 放行 (只看第一条)');
assert.strictEqual(run('CronCreate', path.join(tmp, 'nope.jsonl')), null, 'T7: transcript 读不到放行 (fail-open)');
assert.strictEqual(run('Bash', sched), null, 'T8: 其它工具不管');

fs.rmSync(tmp, { recursive: true, force: true });
console.log('scheduled-task-no-selfloop: 8/8 passed');
