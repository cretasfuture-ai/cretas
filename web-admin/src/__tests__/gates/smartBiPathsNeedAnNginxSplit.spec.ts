/**
 * 前端用共享 `get()` 打的 `/smart-bi/...` 路径，必须**有人**真的在服务它。
 *
 * 2026-08-28 实测：`/api/mobile/F006/smart-bi/analysis/finance` 在测试环境 404。
 * 追下去发现这是一条**跨三个系统才能拼出来的**依赖：
 *
 *   1. 前端用 `get()` 打 `/{factoryId}/smart-bi/analysis/finance`
 *      → baseURL `/api/mobile` → 网关
 *   2. Java **没有**这个 handler（T6.5 PR #205/#236 里被有意删掉了）
 *   3. Python **有**：analysis_finance.py 注册的正是
 *      `/api/mobile/{factory_id}/smart-bi/analysis/finance`
 *   4. 所以只能靠 **nginx 按路径把它分流给 8083**
 *
 * 生产 nginx 确实有这条分流规则 —— 在**被 3 个 vhost 共享 include 的** snippet 里
 * (`/www/server/nginx/conf/snippets/smart-bi-routing.conf`)，`F00[1-46]` 含 F006，
 * 一直在工作。⚠️ 我一开始 grep vhost 文件里的 `location` 判定「规则丢了」——
 * **grep 看不进 include**，差一步就去改生产 nginx。
 *
 * 404 只发生在本地/测试环境：vite dev 代理把 `/api/*` 全给了 Java，没复现那条分流
 * (已在 vite.config.ts 修)。⛔ 判上游不要看响应体形状 —— `smartbi_compat` 就是在
 * 模仿 Java 的信封；要看**时间戳小数位**(Python 6 位 / Java 9 位)。
 *
 * 这道闸不能验 nginx（它不在仓库里）。它做的是把那个隐形依赖**变成显式登记**：
 * 前端打的每一条 `/smart-bi/...`，要么 Java 服务它，要么必须登记在
 * `PYTHON_SERVED_VIA_NGINX` 里。新增一条没登记的，在这里红，而不是上线后 404。
 *
 * ⚠️ 这道闸**不能证明 nginx 规则存在**。它证明的是「这条路径依赖 nginx 分流」
 *    这件事被写下来了。真正的验证只能在部署后打一次那个 URL —— 见 §运维清单。
 */
import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB_SRC = path.resolve(HERE, '../..');
const JAVA_SRC = path.resolve(HERE, '../../../../backend/java/cretas-api/src/main/java');

/**
 * 已知由 Python 提供、依赖 nginx 按路径分流的 `/smart-bi/` 子路径。
 * 每一条都必须能在 backend/python 里找到同名注册。
 */
const PYTHON_SERVED_VIA_NGINX = new Set([
  'analysis/finance',
  'analysis/sales',
  'analysis/kitchen-cost',
  'mapping-review/pending',
  'mapping-review/canonical-fields',
]);

function walk(dir: string, exts: string[]): string[] {
  const out: string[] = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'dist') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p, exts));
    else if (exts.some((x) => e.name.endsWith(x))) out.push(p);
  }
  return out;
}

/** 前端经共享 get/post 打出去的 `/smart-bi/<sub>` 子路径。 */
function frontendSmartBiSubPaths(): Set<string> {
  const re = /\/smart-bi\/((?:analysis|mapping-review)\/[a-z0-9-]+)/g;
  const found = new Set<string>();
  for (const f of walk(WEB_SRC, ['.ts', '.vue'])) {
    if (f.includes('__tests__') || f.endsWith('.spec.ts')) continue;
    const src = fs.readFileSync(f, 'utf8');
    for (const m of src.matchAll(re)) found.add(m[1]);
  }
  return found;
}

/**
 * Java 真正暴露的 `/smart-bi/<sub>`。
 * 类级 `@RequestMapping` 与方法级 mapping 分处两地 —— 直接 grep 拼接后的完整
 * 路径**永远找不到**，那正是这次一开始漏判的原因，所以这里必须拼。
 */
function javaSmartBiSubPaths(): Set<string> {
  const clsRe = /@RequestMapping\("([^"]+)"\)/;
  const mthRe = /@(?:Get|Post|Put|Delete|Patch|Request)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g;
  const out = new Set<string>();
  if (!fs.existsSync(JAVA_SRC)) return out;
  for (const f of walk(JAVA_SRC, ['Controller.java'])) {
    const src = fs.readFileSync(f, 'utf8');
    const cls = clsRe.exec(src);
    if (!cls || !cls[1].includes('/smart-bi')) continue;
    for (const m of src.matchAll(mthRe)) {
      if (m.index !== undefined && m.index < cls.index + cls[0].length) continue;
      out.add(m[1].replace(/^\//, ''));
    }
  }
  return out;
}

describe('闸: 前端打的 /smart-bi 路径必须有人服务', () => {
  it('前置条件: 能读到 Java 源码, 否则这道闸什么都没在量', () => {
    expect(fs.existsSync(JAVA_SRC)).toBe(true);
    expect(javaSmartBiSubPaths().size).toBeGreaterThan(0);
  });

  it('前端每条 /smart-bi 子路径, 要么 Java 服务, 要么已登记为 Python+nginx', () => {
    const java = javaSmartBiSubPaths();
    const unaccounted = [...frontendSmartBiSubPaths()].filter(
      (p) => !java.has(p) && !PYTHON_SERVED_VIA_NGINX.has(p),
    );
    expect(unaccounted).toEqual([]);
  });

  it('登记表里不许有 Java 其实已经服务了的条目 (否则登记就过期了)', () => {
    const java = javaSmartBiSubPaths();
    const stale = [...PYTHON_SERVED_VIA_NGINX].filter((p) => java.has(p));
    expect(stale).toEqual([]);
  });

  it('登记的每一条都能在 backend/python 里找到同名注册', () => {
    const pyRoot = path.resolve(HERE, '../../../../backend/python');
    if (!fs.existsSync(pyRoot)) return;                       // 单仓拆分时跳过
    const sources = walk(pyRoot, ['.py'])
      .filter((f) => !f.includes('venv') && !f.includes('site-packages'))
      .map((f) => fs.readFileSync(f, 'utf8'))
      .join('\n');
    // ⚠️ 不能只找拼接后的完整串。Python 侧常写成
    //     _BASE = "/api/mobile/{factory_id}/smart-bi/mapping-review"
    //     @router.get(_BASE + "/pending")
    // 完整路径在源码里**从不以字面量出现** —— 这道闸第一版就因此把
    // mapping-review/* 误报成「Python 也没有」。这与本次事故一开始漏判
    // Java 的 SmartBIAnalysisController 是同一个错：类级 + 方法级分处两地。
    const missing = [...PYTHON_SERVED_VIA_NGINX].filter((p) => {
      if (sources.includes(`/smart-bi/${p}`)) return false;         // 完整字面量
      const [prefix, suffix] = p.split('/');
      return !(sources.includes(`/smart-bi/${prefix}`) && sources.includes(`"/${suffix}"`));
    });
    expect(missing).toEqual([]);
  });
});
