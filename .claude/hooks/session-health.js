#!/usr/bin/env node
/**
 * session-health.js — UserPromptSubmit hook: 只提醒、不拦截。
 *
 * 目的: 替用户判断「什么时候该换 session」。读当前会话 transcript, 算出
 *   1) 当前上下文体积(最近一次 assistant usage 的 input + cache 三项)
 *   2) 本会话已压缩次数(system/compact_boundary 行数)
 * 越过阈值时, 通过 additionalContext 让 Claude 在下一个自然节点主动写交接并建议换会话。
 *
 * 阈值依据(2026-11-13 Steve 拍板 300K 软 / 400K 硬): 用 11-09~11-12 共 67 个会话的真实「每轮上下文增长」回放,
 *   新会话起步约 150K(起步中位数 126K + 读交接), 只算读缓存:
 *   250K 省 44% / 换 208 次, 300K 省 39% / 换 130 次, 400K 省 30% / 换 74 次, 600K 省 10% / 换 33 次;
 *   300→250 只多省 5 个点却多换 78 次, 所以软线 300K(到任务边界再换)、硬线 400K(到安全停止点就换)。
 *   更早的依据: 131 个会话(07-29~09-21)压缩几乎都在 ~1M, 成本中位数 0 次压缩 $126 / 1 次 $400 / 2 次 $570。
 *
 * 安静: 同一级别每个会话只提醒一次; L2 之后每再涨 100K 提醒一次。
 * 失败策略: 全程 fail-silent, 任何异常都输出空, 不影响会话。
 * 卸载: 删 settings.json 里 hooks.UserPromptSubmit 中指向本文件的那一项。
 * 调阈值: 改下面 CONFIG, 或设环境变量 SESSION_HEALTH_SOFT_K / SESSION_HEALTH_HARD_K / SESSION_HEALTH_MAX_COMPACT。
 */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const CONFIG = {
  softTokens: (Number(process.env.SESSION_HEALTH_SOFT_K) || 300) * 1000,   // L1
  hardTokens: (Number(process.env.SESSION_HEALTH_HARD_K) || 400) * 1000,   // L2
  maxCompactions: Number(process.env.SESSION_HEALTH_MAX_COMPACT) || 2,     // 压缩 >= 此数即 L2
  reRemindStep: 100 * 1000,
};

const ROOT = path.join(os.homedir(), '.claude', 'context-handoff');
const STATE_DIR = path.join(ROOT, 'health');
const HANDOFF_DIR = path.join(ROOT, 'handoff');

function main(input) {
  const sid = String(input.session_id || '');
  const tp = input.transcript_path;
  if (!sid || !tp || !fs.existsSync(tp)) return null;

  fs.mkdirSync(STATE_DIR, { recursive: true });
  const sp = path.join(STATE_DIR, sid.replace(/[^A-Za-z0-9_-]/g, '_') + '.json');
  let st = { offset: 0, compactions: 0, ctx: null, level: 0, remindedAt: 0 };
  try { st = Object.assign(st, JSON.parse(fs.readFileSync(sp, 'utf8'))); } catch (_) { /* 首次 */ }

  const size = fs.statSync(tp).size;
  if (size < st.offset) { st.offset = 0; st.compactions = 0; st.ctx = null; } // 文件被换过, 重扫

  // 增量读取: 只读上次之后新增的字节
  const fd = fs.openSync(tp, 'r');
  let chunk = '';
  try {
    const len = size - st.offset;
    if (len > 0) {
      const b = Buffer.alloc(len);
      fs.readSync(fd, b, 0, len, st.offset);
      chunk = b.toString('utf8');
    }
  } finally { fs.closeSync(fd); }

  // 末尾可能是写了一半的行: 只处理到最后一个换行, offset 只推进到那里
  const cut = chunk.lastIndexOf('\n');
  const complete = cut >= 0 ? chunk.slice(0, cut + 1) : '';
  const consumed = Buffer.byteLength(complete, 'utf8');

  for (const line of complete.split('\n')) {
    if (!line) continue;
    if (line.includes('"subtype":"compact_boundary"')) {
      st.compactions += 1;
      st.ctx = null; // 压缩后体积重新取
      continue;
    }
    if (line.includes('"type":"assistant"') && line.includes('"usage"') && !line.includes('"isSidechain":true')) {
      try {
        const r = JSON.parse(line);
        const u = r && r.message && r.message.usage;
        if (u) {
          const t = (u.input_tokens || 0) + (u.cache_creation_input_tokens || 0) + (u.cache_read_input_tokens || 0);
          if (t > 0) st.ctx = t;
        }
      } catch (_) { /* 跳过坏行 */ }
    }
  }
  st.offset += consumed;

  const ctx = st.ctx || 0;
  let level = 0;
  if (ctx >= CONFIG.softTokens || st.compactions >= 1) level = 1;
  if (ctx >= CONFIG.hardTokens || st.compactions >= CONFIG.maxCompactions) level = 2;

  let speak = false;
  if (level > st.level) speak = true;
  else if (level === 2 && ctx >= st.remindedAt + CONFIG.reRemindStep && st.remindedAt > 0) speak = true;

  if (speak) { st.level = level; st.remindedAt = ctx; }
  fs.writeFileSync(sp + '.tmp', JSON.stringify(st), 'utf8');
  fs.renameSync(sp + '.tmp', sp);
  if (!speak) return null;

  const k = (n) => Math.round(n / 1000) + 'K';
  const handoff = path.join(HANDOFF_DIR, sid.slice(0, 8) + '.md').replace(/\\/g, '/');
  const head = `[session-health] 当前上下文约 ${ctx ? k(ctx) : '未知'}，本会话已压缩 ${st.compactions} 次。`;
  const how = [
    `交接写到 ${handoff}（不进仓库），≤ 15 行：目标与完成条件 / 已定的决定和原因 / 已做完 vs 没做完 / 试过但放弃的路 / 分支、HEAD、PR 与 CI 监视 / 下一步第一个动作。`,
    '然后：organizer 会话直接用 start_session 开新会话接手（不问用户，开完用 get_session 核对模型）；执行会话写完交接后告诉用户/organizer「已收口，交接在上面这个路径」。',
    '新会话的模型按 organizer 技能里的风险分档选（缓存本来就要重建，换会话时换模型最划算）。',
  ];
  const soft = [
    head + '（软线 300K）',
    '不要打断手上的步骤。到下一个任务边界（PR 合并 / 一件事做完 / 一个切片验收完）就收口换会话，不要再开新的一件事。',
    ...how,
  ];
  const hard = [
    head + '（硬线 400K）',
    '到下一个安全停止点就收口，即使这件事还没做完：先把正在跑的一步做完（不在半截停下，不留未提交的半成品），再写交接换会话。',
    ...how,
  ];
  return (level === 2 ? hard : soft).join('\n');
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => { buf += d; });
process.stdin.on('end', () => {
  try {
    const input = JSON.parse(buf || '{}');
    const text = main(input);
    if (text) {
      process.stdout.write(JSON.stringify({
        hookSpecificOutput: { hookEventName: 'UserPromptSubmit', additionalContext: text },
      }));
    }
  } catch (_) { /* fail-silent */ }
});
