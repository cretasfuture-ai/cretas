#!/usr/bin/env node
// PreToolUse 闸: mcp__ccd_session_mgmt__archive_session
//
// 归档一个会话时, 桌面应用会把挂在它名下、空闲且无开着 PR 的子会话一起归档。
// organizer 交班时如果老 organizer 名下还挂着在飞执行线或新 organizer 本身,
// 归档老 organizer 就会把它们连带归档 (2026-11-13 交班演练发现)。
// 本闸读桌面应用的本机会话记录, 目标名下仍有「未 detach 且未归档」的子会话就拒绝,
// 并列出这些子会话。想连带归档的, 先逐个单独归档子会话, 再归档父会话。
//
// 接线: 挂在用户级 ~/.claude/settings.json 的 PreToolUse (command 指向主目录副本)。
// ⛔ 不要挂在项目 .claude/settings.json: 2026-11-13 实测 worktree 里新加的项目级 PreToolUse/PostToolUse
// 在新会话里都不触发 (原因未证实, 同文件原有的 Stop/SessionStart 正常); 用户级热加载且必触发。
//
// 三态 (measurement-and-wiring 形态 E):
//   放行   = 读到会话记录, 目标名下没有挂着的子会话
//   拒绝   = 读到会话记录, 有挂着的子会话
//   没量到 = 读不到会话记录 / 找不到目标 -> 放行但发 systemMessage 说明闸没生效
'use strict';
const fs = require('fs');
const path = require('path');

function storeRoot() {
  if (process.env.CRETAS_CCD_SESSION_STORE) return process.env.CRETAS_CCD_SESSION_STORE;
  const appData = process.env.APPDATA || path.join(process.env.HOME || '', 'AppData', 'Roaming');
  return path.join(appData, 'Claude', 'claude-code-sessions');
}

function loadRecords(root) {
  const out = [];
  if (!fs.existsSync(root)) return null;
  for (const a of fs.readdirSync(root)) {
    const da = path.join(root, a);
    if (!fs.statSync(da).isDirectory()) continue;
    for (const b of fs.readdirSync(da)) {
      const db = path.join(da, b);
      if (!fs.statSync(db).isDirectory()) continue;
      for (const f of fs.readdirSync(db)) {
        if (!/^local_.*\.json$/.test(f)) continue;
        try {
          out.push(JSON.parse(fs.readFileSync(path.join(db, f), 'utf8')));
        } catch (_) { /* 写到一半的文件跳过 */ }
      }
    }
  }
  return out;
}

let target = '';
function emit(obj) {
  // 每次调用必写一行 (硬约束 9: 「没拦」要有「一定会发生」的读数证明闸确实跑了)
  const verdict = obj.hookSpecificOutput ? 'deny' : obj.systemMessage ? 'unmeasured' : 'allow';
  try {
    const log = process.env.CRETAS_ARCHIVE_GUARD_LOG || path.join(require('os').tmpdir(), 'cretas-archive-guard.log');
    fs.appendFileSync(log, `${new Date().toISOString()} ${verdict} ${target}\n`);
  } catch (_) { /* 日志写不了不影响判定 */ }
  process.stdout.write(JSON.stringify(obj));
  process.exit(0);
}

function unmeasured(why) {
  emit({ systemMessage: `archive-guard 没量到 (${why}), 本次归档未经子会话检查; 请先用 get_session 核对名下子会话都已 detached。` });
}

let raw = '';
process.stdin.on('data', (c) => { raw += c; });
process.stdin.on('end', () => {
  let input;
  try { input = JSON.parse(raw || '{}'); } catch (_) { return unmeasured('hook 输入不是 JSON'); }
  target = (input.tool_input && input.tool_input.session_id) || '';
  if (!target) return unmeasured('tool_input 没有 session_id');

  const records = loadRecords(storeRoot());
  if (!records || records.length === 0) return unmeasured('读不到本机会话记录');

  if (target === 'self') {
    const me = records.find((r) => r.cliSessionId === input.session_id);
    if (!me) return unmeasured('找不到 self 对应的会话记录');
    target = me.sessionId;
  }
  if (!records.some((r) => r.sessionId === target)) return unmeasured(`找不到目标 ${target} 的会话记录`);

  const attached = records.filter((r) =>
    r.spawnedFrom && r.spawnedFrom.sessionId === target && r.lineageDetached !== true && r.isArchived !== true);
  if (attached.length === 0) emit({});

  const lines = attached.map((r) => {
    const open = Array.isArray(r.prs) ? r.prs.filter((p) => p && p.state === 'OPEN') : [];
    const prs = open.length ? ` 开着的 PR #${open.map((p) => p.prNumber).join(',#')}` : '';
    return `- ${r.title || '(无标题)'} (${r.sessionId})${prs}`;
  });
  emit({
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason:
        `archive-guard: ${target} 名下还有 ${attached.length} 个未 detach 的子会话, 归档会把空闲的一并归档:\n` +
        lines.join('\n') +
        '\n要保留的: 让父会话对它们 detach_session (交班时老 organizer 连新 organizer 一起 detach), 用 get_session 核对 detached:true 后再归档。' +
        '\n确实要一起归档的: 先逐个单独 archive_session 子会话, 再归档父会话。',
    },
  });
});
