import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 请求路径里 ⛔ 不许再写 `/api/mobile` —— axios 的 baseURL 已经是它。
 *
 * <h2>🔴 2026-08-28 全量走查抓到（接口层抓不到，因为它「成功地」返回了 403）</h2>
 *
 * <p>`aiValueSummary.ts` 写成 <code>get(`/api/mobile/${factoryId}/ai/value-summary`)</code>，
 * 而 <code>request.ts:141</code> 的实例是
 * <code>baseURL: VITE_API_BASE_URL || '/api/mobile'</code> ⇒ 实际发出去的是</p>
 *
 * <pre>/api/mobile/api/mobile/F006/ai/value-summary   → 403</pre>
 *
 * <p>🔴 <b>而页面告诉用户的是别的事</b>：</p>
 *
 * <pre>
 *   权限不足。以下不显示任何数字 —— 查不到和没发生是两件事。
 *   无权访问该工厂数据  请检查是否访问了错误的工厂, 或切换到有权限的账号重试
 * </pre>
 *
 * <p>⇒ <b>它让用户去换账号，而真因是 URL 拼错了。换多少个账号都不会好。</b>
 * 空态文案本身写得对（「查不到和没发生是两件事」正是本仓的纪律），
 * 只是挂在了<b>错误的诊断</b>上 —— 403 被无条件解释成「权限不足」。</p>
 *
 * <h2>判据</h2>
 *
 * <p>扫 <code>web-admin/src</code> 下所有非测试的 <code>.ts</code> / <code>.vue</code>，
 * 断言没有任何 <code>get/post/put/del/patch</code> 调用的<b>第一个参数</b>以
 * <code>/api/mobile</code> 开头。</p>
 *
 * <p>⚠️ <b>不</b>禁止字符串 <code>'/api/mobile'</code> 本身出现 ——
 * <code>request.ts</code> 自己要用它做 baseURL，<code>adminGet</code> 那一族
 * 也**故意**用绝对路径绕开 baseURL（见 <code>request.ts:597</code> 的注释）。
 * ⇒ 判据只盯「请求函数的路径参数」这一个形状，⛔ 不做全文禁字。</p>
 *
 * <p>建闸当天违例 <b>0</b> 处（那一处已在同一个 PR 里修掉）⇒ 硬红，不需要棘轮。</p>
 */

const SRC = resolve(__dirname, '../..');

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === '__tests__') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.ts') || name.endsWith('.vue')) out.push(p);
  }
  return out;
}

function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split('\n')
    .map(l => l.replace(/\/\/.*$/, ''))
    .join('\n');
}

/** `get('/api/mobile...`、`post(`/api/mobile...` 这种形状 —— 路径参数以 /api/mobile 开头。 */
const BAD = /\b(?:get|post|put|del|delete|patch)\s*\(\s*[`'"]\/api\/mobile/;

describe('请求路径不许双前缀 /api/mobile', () => {
  const files = walk(SRC);

  it('阳性对照: 真的扫到文件了, 且这条正则认得出那个形状', () => {
    // 🔴 没有这一条, 文件列表为空时下面的断言恒真
    expect(files.length).toBeGreaterThan(200);
    expect(BAD.test("return get(`/api/mobile/${factoryId}/x`)")).toBe(true);
  });

  it('阴性对照: 正确写法不被误报', () => {
    expect(BAD.test('return get(`/${factoryId}/governance/skills`)')).toBe(false);
    // request.ts 自己拿它当 baseURL、adminGet 故意用绝对路径 —— 都不该被这条正则命中
    expect(BAD.test("baseURL: import.meta.env.VITE_API_BASE_URL || '/api/mobile'")).toBe(false);
  });

  it('⛔ 没有任何请求函数的路径参数以 /api/mobile 开头', () => {
    const hits: string[] = [];
    for (const f of files) {
      const body = stripComments(readFileSync(f, 'utf8'));
      body.split('\n').forEach((line, i) => {
        if (BAD.test(line)) hits.push(`${relative(SRC, f).split(sep).join('/')}:${i + 1}  ${line.trim().slice(0, 100)}`);
      });
    }
    expect(
      hits,
      `axios 的 baseURL 已经是 /api/mobile (request.ts:141)。这里再写一遍 ⇒ 实际请求变成\n` +
        `  /api/mobile/api/mobile/...  → 403\n` +
        `而页面会把 403 解释成「权限不足, 请换账号」—— 用户照做也永远好不了。\n` +
        `修法: 去掉路径里的 /api/mobile, 写成 \`/\${factoryId}/...\`。\n` +
        hits.join('\n'),
    ).toEqual([]);
  });
});
