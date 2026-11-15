#!/usr/bin/env node
// 统一原型构建锁。各线只能通过本脚本拿锁、放锁，避免误删别人的锁（11-11 已发生两次）。
// 用法：
//   node build-lock.cjs run <线> -- <命令...>   拿锁 → 执行命令 → 无论成败都只释放自己的锁（推荐）
//   node build-lock.cjs acquire <线> [等待秒数=1200]   退出码 0=拿到；75=超时仍被占用
//   node build-lock.cjs release <线>   只在锁属于该线时删除；不属于时退出码 3，不删
//   node build-lock.cjs status
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const LOCK = path.join(__dirname, '..', 'locks', 'build.lock');
const sleep = ms => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

function read() {
  try { return JSON.parse(fs.readFileSync(LOCK, 'utf8')); } catch (e) {
    if (e.code === 'ENOENT') return null;
    return { line: '（无法解析）', raw: String(fs.readFileSync(LOCK, 'utf8')).slice(0, 200) };
  }
}

function acquire(line, waitSec) {
  const deadline = Date.now() + waitSec * 1000;
  for (;;) {
    try {
      fs.mkdirSync(path.dirname(LOCK), { recursive: true });
      fs.writeFileSync(LOCK, JSON.stringify({ line, at: new Date().toISOString(), pid: process.pid }), { flag: 'wx' });
      console.log(`LOCK_ACQUIRED line=${line}`);
      return true;
    } catch (e) {
      if (e.code !== 'EEXIST') throw e;
      const h = read();
      if (Date.now() >= deadline) {
        console.log(`LOCK_TIMEOUT held_by=${h && h.line} since=${h && h.at}`);
        return false;
      }
      console.log(`LOCK_BUSY held_by=${h && h.line} since=${h && h.at}; 10 秒后重试`);
      sleep(10000);
    }
  }
}

function release(line) {
  const h = read();
  if (!h) { console.log('LOCK_ALREADY_FREE'); return 0; }
  if (h.line !== line) { console.log(`LOCK_NOT_YOURS held_by=${h.line}; 未删除`); return 3; }
  fs.unlinkSync(LOCK);
  console.log(`LOCK_RELEASED line=${line}`);
  return 0;
}

const [, , cmd, line, ...rest] = process.argv;
if (cmd === 'status') { console.log(JSON.stringify(read())); process.exit(0); }
if (!line) { console.error('缺少 <线> 参数'); process.exit(2); }
if (cmd === 'acquire') process.exit(acquire(line, Number(rest[0] || 1200)) ? 0 : 75);
if (cmd === 'release') process.exit(release(line));
if (cmd === 'run') {
  const i = rest.indexOf('--');
  const argv = i >= 0 ? rest.slice(i + 1) : rest;
  if (!argv.length) { console.error('run 需要在 -- 后给出命令'); process.exit(2); }
  if (!acquire(line, 1200)) process.exit(75);
  let code = 1;
  // 每次 run 的输出另存一份到 locks/build-logs/（审计 BATCH6 流程-2：构建证据要落盘）
  const logDir = require('path').join(__dirname, '..', 'locks', 'build-logs');
  const stamp = new Date(Date.now() + 8 * 3600e3).toISOString().replace(/[-:]/g, '').slice(0, 15);
  const logFile = require('path').join(logDir, `build-${stamp}-${line}.log`);
  // 输出直接写进日志文件（不用管道）：restart.ps1 拉起的常驻服务会继承输出句柄，
  // 用管道时 spawnSync 要等管道关闭才返回，锁会一直占着（C 11-13 报）；写文件时只等命令本身退出。
  const fs = require('fs');
  let fd = null;
  try { fs.mkdirSync(logDir, { recursive: true }); fd = fs.openSync(logFile, 'a'); fs.writeSync(fd, `# line=${line} cmd=${argv.join(' ')}\n`); } catch (e) { console.error('构建日志打开失败：' + e.message); }
  try {
    const r = spawnSync(argv[0], argv.slice(1), { stdio: fd === null ? 'inherit' : ['inherit', fd, fd], shell: process.platform === 'win32' });
    code = r.status === null ? 1 : r.status;
  } finally {
    release(line);
  }
  if (fd !== null) {
    try { fs.writeSync(fd, `\n# RUN_EXIT=${code}\n`); fs.closeSync(fd); } catch {}
    try { const txt = fs.readFileSync(logFile, 'utf8'); process.stdout.write(txt.length > 20000 ? '…（前略，全文见日志）\n' + txt.slice(-20000) : txt); } catch {}
    console.log(`BUILD_LOG=${logFile}`);
  }
  console.log(`RUN_EXIT=${code}`);
  process.exit(code);
}
console.error('未知子命令'); process.exit(2);
