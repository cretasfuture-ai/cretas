import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 前端读的统计字段名，必须真的是后端产出的那些。
 *
 * <h2>🔴 2026-08-28 视觉验收抓到（接口层抓不到）</h2>
 *
 * <p>质检标准页四张 KPI 卡：<code>质检项总数 1 / 已启用 0 / 必检项 0 / 严重级别 0</code>，
 * 而同屏表格里唯一那条质检项的状态标签写着 <b>「启用」</b>。
 * 生产实测（F006）：<code>quality_check_items</code> 1 条且 <code>enabled = true</code>
 * ⇒ <b>表格对，KPI 卡错</b>。</p>
 *
 * <p>根因是<b>四个字段名一个都对不上</b>：</p>
 *
 * <pre>
 *   前端读                     后端产出              结果
 *   statistics.totalItems      total                undefined → 回退 tableData.length（碰巧像对的）
 *   statistics.enabledItems    (后端没有这个概念)     undefined → 0   🔴
 *   statistics.requiredItems   requiredCount        undefined → 0   （真值也是 0，看不出来）
 *   statistics.criticalItems   criticalCount        undefined → 0   （同上）
 * </pre>
 *
 * <p>⚠️ 它能活着，是因为<b>三个真值恰好是 0</b>，第四个有 <code>|| tableData.length</code> 兜底
 * ——「兜底把不知道翻译成 0」，而 0 在这里长得和真实读数一模一样。
 * ⛔ 那个兜底本身还是错的：<code>tableData</code> 是<b>当前页</b>，超过一页就少报。</p>
 *
 * <h2>这道闸为什么从后端源码推导，而不是在这里再写一份字段清单</h2>
 *
 * <p>字段名是<b>跨语言契约</b>，天然存在于两处。在前端硬写一份「应该有哪些字段」的清单，
 * 就是「同一个东西两份，它一定会漂」——漂了以后闸自己也不知道。
 * ⇒ <b>唯一真值取后端</b>：扫 <code>QualityCheckItemServiceImpl.getStatistics()</code> 里
 * 实际 <code>stats.put("X", ...)</code> 的键，断言 Vue 模板读的每一个
 * <code>statistics.Y</code> 都在那个集合里。</p>
 *
 * <p>⚠️ 已知盲区：它只管<b>名字对不对</b>，不管<b>值算得对不对</b>；
 * 也不管后端产出了而前端没用的键（那不是缺陷）。</p>
 */

const REPO = resolve(__dirname, '../../../../../..');
const JAVA = resolve(
  REPO,
  'backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QualityCheckItemServiceImpl.java',
);
const VUE = resolve(__dirname, '../list.vue');

/** 逐行剥注释 —— 本仓多次栽在「闸把自己写的注释当被测对象」上。 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split('\n')
    .map(line => line.replace(/\/\/.*$/, ''))
    .join('\n');
}

/** 后端 getStatistics() 里真正 put 进去的键。 */
function backendKeys(): Set<string> {
  const src = stripComments(readFileSync(JAVA, 'utf8'));
  const body = src.slice(src.indexOf('getStatistics'));
  const end = body.indexOf('\n    }');
  const scope = end > 0 ? body.slice(0, end) : body;
  return new Set([...scope.matchAll(/stats\.put\("([A-Za-z]+)"/g)].map(m => m[1]));
}

/**
 * Vue <template> 里读的 statistics.X。
 *
 * ⚠️ 只扫 <template>：<script setup> 里 `statistics` 是个 ref，那里的
 * `statistics.value` 是 ref 解包，不是一个 KPI 字段。第一版扫全文件，
 * 于是把 `value` 报成了「后端不产出的字段」—— 尺子过宽造的假阳性，不是代码错。
 */
function frontendKeys(): Set<string> {
  const raw = readFileSync(VUE, 'utf8');
  const start = raw.indexOf('<template>');
  const end = raw.lastIndexOf('</template>');
  if (start < 0 || end < 0) throw new Error('没找到 <template> 段 —— 这把尺子失效了');
  const tpl = stripComments(raw.slice(start, end));
  return new Set([...tpl.matchAll(/statistics\.([A-Za-z]+)/g)].map(m => m[1]));
}

describe('质检标准 KPI: 前端字段名必须是后端产出的', () => {
  it('阳性对照: 两侧都真的解析出了键（否则下面的比较是恒真的）', () => {
    // 🔴 没有这一条，「前端的键 ⊆ 后端的键」在两边都是空集时也成立
    expect(backendKeys().size).toBeGreaterThanOrEqual(4);
    expect(frontendKeys().size).toBeGreaterThanOrEqual(4);
  });

  it('阴性对照: 这把尺子认得出一个不存在的键', () => {
    expect(backendKeys().has('__NoSuchKeyEverPut__')).toBe(false);
  });

  it('前端读的每一个 statistics.X 都在后端的 put 集合里', () => {
    const backend = backendKeys();
    const missing = [...frontendKeys()].filter(k => !backend.has(k));
    expect(missing, `这些字段后端不产出 ⇒ 页面会显示「—」而不是真值。后端实际有: ${[...backend].sort().join(', ')}`)
      .toEqual([]);
  });

  it('⛔ 不许用 `|| 0` 兜底 KPI —— 那会把「字段名错了」伪装成「真值是 0」', () => {
    const tpl = stripComments(readFileSync(VUE, 'utf8'));
    expect(tpl).not.toMatch(/statistics\.[A-Za-z]+\s*\|\|\s*0/);
    // 也不许拿【当前页】行数当总数
    expect(tpl).not.toMatch(/statistics\.[A-Za-z]+\s*\|\|\s*tableData\.length/);
  });
});
