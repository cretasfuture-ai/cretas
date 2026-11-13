#!/usr/bin/env node
// PreToolUse 闸: 定时任务 (桌面应用 scheduled task) 的运行会话里, 禁止自我续命和改定时任务。
//
// 拦: ScheduleWakeup / CronCreate / mcp__scheduled-tasks__{create,update,delete,run}_scheduled_task。
// 放: mcp__scheduled-tasks__list_scheduled_tasks / list_task_runs (检查任务要读它们)。
//
// 依据: pomelox 项目 2026-09-26 巡检一轮为等子代理调了 ScheduleWakeup, 自驱循环跑了 14 小时, 与 cron 轮次重复,
//   会话一直「活着」无法归档; 改定时任务的工具在无人值守会话里会卡在批准框上。11-13 从 pomelox 搬来。
//   Cretas 的定时检查都是「只出结论, 不改配置」, 没完成的事写进发现台账 (check-findings.jsonl) 交下一轮。
//
// 判定: transcript 第一条消息 (queue-operation 的 content 或第一条 user 文本) 以 <scheduled-task name= 开头。
//   只看第一条, 普通会话里粘贴过任务提示词不会误拦。
// 三态: 拦 = 定时任务会话 + 写类工具; 放 = 不是定时任务会话 / 读类工具; 没量到 (transcript 读不到) = 放行
//   (fail-open, 与不装这个闸一致), 不发提示以免普通会话刷屏。
// 接线: 用户级 ~/.claude/settings.json PreToolUse, matcher 「ScheduleWakeup|CronCreate|mcp__scheduled-tasks__.*」,
//   command 指向主目录副本 (项目级 PreToolUse 在 worktree 新会话里不触发, 见 archive-guard.js 头注释)。
'use strict';
const fs = require('fs');

const WRITE_TOOLS = /^(ScheduleWakeup|CronCreate|mcp__scheduled-tasks__(create|update|delete|run)_scheduled_task)$/;

function firstText(tp) {
  let s;
  try {
    const fd = fs.openSync(tp, 'r');
    const b = Buffer.alloc(256 * 1024);
    const n = fs.readSync(fd, b, 0, b.length, 0);
    fs.closeSync(fd);
    s = b.slice(0, n).toString('utf8');
  } catch (_) { return null; }
  for (const line of s.split('\n').slice(0, 200)) {
    let e;
    try { e = JSON.parse(line); } catch (_) { continue; }
    if (e.type === 'queue-operation' && typeof e.content === 'string') return e.content;
    if (e.type === 'user') {
      const c = e.message && e.message.content;
      if (typeof c === 'string') return c;
      if (Array.isArray(c)) { const t = c.find((x) => x && x.type === 'text'); if (t) return t.text || ''; }
    }
  }
  return null;
}

function decide(input) {
  if (!WRITE_TOOLS.test(String(input.tool_name || ''))) return null;
  const tp = input.transcript_path;
  if (!tp) return null;
  const t = firstText(tp);
  if (t === null || !t.trimStart().startsWith('<scheduled-task name=')) return null;
  return {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason:
        '本会话是定时任务的一次运行: 禁止用 ScheduleWakeup / CronCreate 自行续命, 也不新建/修改/触发定时任务 (无人值守会卡在批准框, 续命会和下一轮重复)。' +
        '没做完的事 (等结果、待复检、建议的修复) 按任务说明写进 ~/.claude/context-handoff/check-findings.jsonl 交下一轮或 organizer, 然后正常结束本轮。',
    },
  };
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => { buf += d; });
process.stdin.on('end', () => {
  try {
    const out = decide(JSON.parse(buf || '{}'));
    if (out) process.stdout.write(JSON.stringify(out));
  } catch (_) { /* fail-open */ }
  process.exit(0);
});
