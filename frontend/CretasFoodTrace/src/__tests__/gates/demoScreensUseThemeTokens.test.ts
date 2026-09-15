/**
 * 演示三屏只用主题 token（FEAT-RN-DEMO-SCREENS-POLISH-20260915 / 通用条）。
 *
 * 2026-09-15 真机实测这几屏各自散落着 34 个硬编码 hex（`#FF9800` / `#5D4037` / `#1A57B5` …）
 * 与 22 处 `fontSize: 10~13`（低于 14sp 下限）。polish 把它们全部换成 `useTheme()` 的 token
 * 与 Paper 的 `variant`。本闸守住别再漂回去：
 *
 *   ① 剥掉注释之后，源码里 `#RRGGBB` 计数 = 0
 *   ② 剥掉注释之后，`fontSize: 10..13` 计数 = 0
 *
 * ⚠️ 阳性对照不能省：闸的两条正则 + 剥注释器对一段**故意带违例**的源码必须数得出来，
 *    否则「0」可能只是「正则死了」或「剥注释器把整份源码剥空了」。
 *
 * 变异对照（2026-09-15 做过）：在 LineageChainTimeline 的 StyleSheet 里写回一个
 * `backgroundColor: '#5D4037'` ⇒ ① 红（并把文件名与那一段打出来）。
 *
 * 2026-09-16（demo-nav-shell）：`screens/shared/VisionVerifyScreen.tsx` 原本登记在「故意不扫」
 * 里（存量 7 个 hex：`#f5f7fa` / `#718096`×3 / `#4a5568` / `#c05621` / `#fefcbf`）。
 * 本轮把它们全部换成 token 后加进 GUARDED_FILES；变异对照：在它的 StyleSheet 里写回
 * `container: { flex: 1, backgroundColor: '#f5f7fa' }` ⇒ ① 红。
 * 「故意不扫」清单现在为空 —— 再要豁免文件，按硬约束 8 在这里登记并写理由。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.resolve(__dirname, '../..');

/**
 * 被守的文件：四个屏 + 两个溯源组件 + 两个色板文件（色板自己更不许有色值）
 * + 溯源入口 / 只读个体列表 + AI 问答屏与它的快捷卡组（2026-09-16 第五幕 polish：62 hex / 17 fontSize → 0）。
 */
export const GUARDED_FILES = [
  'screens/factory-admin/home/BossOverviewScreen.tsx',
  'screens/traceability/TraceabilityDetailScreen.tsx',
  'screens/traceability/components/LineageChainTimeline.tsx',
  'screens/traceability/components/SourceIndividualBanner.tsx',
  'screens/shared/SourceIndividualDetailScreen.tsx',
  'screens/shared/VisionVerifyScreen.tsx',
  'screens/traceability/components/dnaStatus.tsx',
  'screens/traceability/components/statusTone.tsx',
  // 2026-09-16 (demo-entry-list): 溯源入口重做 + 新建只读个体列表, 两屏从写下第一行起就在闸里。
  'screens/traceability/TraceabilityScreen.tsx',
  'screens/shared/SourceIndividualListScreen.tsx',
  // 2026-09-16 (demo-ai-chat): AI 问答屏 + 快捷卡组, 62 hex / 17 fontSize 清零后登记。
  'screens/factory-admin/ai-analysis/AIChatScreen.tsx',
  'components/ai/QuickActionCardGrid.tsx',
] as const;

const HEX6 = /#[0-9A-Fa-f]{6}\b/g;
const SMALL_FONT = /fontSize:\s*1[0-3]\b/g;

/**
 * 剥注释：块注释整段去掉；行注释从 `//` 到行尾去掉。
 * ⚠️ 只在 `//` 前面不是 `:` 时才当行注释（`http://` 这种不算）—— 这几份文件没有 URL，
 *    但闸不该依赖这个巧合。
 */
export function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

function offenders(source: string, re: RegExp): string[] {
  const out: string[] = [];
  const lines = stripComments(source).split('\n');
  lines.forEach((line, i) => {
    const m = line.match(re);
    if (m) out.push(`L${i + 1}: ${line.trim()}  ← ${m.join(', ')}`);
  });
  return out;
}

describe('阳性对照：正则与剥注释器都活着', () => {
  const sample = [
    "const styles = StyleSheet.create({",
    "  // 行注释里的 #ABCDEF 不算",
    "  /* 块注释里的 #123456 也不算, fontSize: 11 也不算 */",
    "  bad: { backgroundColor: '#123456', fontSize: 12 },",
    "  ok: { fontSize: 14 },",
    "});",
  ].join('\n');

  it('字符串里的 #123456 与 fontSize: 12 各数到 1 条', () => {
    expect(offenders(sample, HEX6)).toHaveLength(1);
    expect(offenders(sample, SMALL_FONT)).toHaveLength(1);
  });

  it('注释里的违例被剥掉，⛔ 不数（否则文件头的说明会让闸恒红、被人关掉）', () => {
    // 剥注释之后不再含注释里那两个
    expect(stripComments(sample)).not.toMatch(/#ABCDEF/);
    expect(stripComments(sample)).not.toMatch(/#123456 也不算/);
    // 但字符串里那个还在（剥注释器没把整份源码剥空）
    expect(stripComments(sample)).toMatch(/'#123456'/);
  });

  it('fontSize: 14 不算违例（下限是 14sp，恰好 14 合规）', () => {
    expect(offenders("x: { fontSize: 14 }", SMALL_FONT)).toHaveLength(0);
    expect(offenders("x: { fontSize: 13 }", SMALL_FONT)).toHaveLength(1);
    expect(offenders("x: { fontSize: 130 }", SMALL_FONT)).toHaveLength(0);
  });
});

describe('被守的文件', () => {
  it('阳性对照：每个文件都真的读到了内容（不是空文件 / 路径打错）', () => {
    for (const rel of GUARDED_FILES) {
      const source = fs.readFileSync(path.join(SRC, rel), 'utf8');
      expect(source.length).toBeGreaterThan(500);
      // 它们都从主题取 token（useTheme 或 AppTheme 类型）—— 证明读到的是改过之后的那份
      expect(source).toMatch(/useTheme|AppTheme/);
    }
  });

  it.each(GUARDED_FILES)('%s：剥注释后没有 #RRGGBB', (rel) => {
    const source = fs.readFileSync(path.join(SRC, rel), 'utf8');
    expect(offenders(source, HEX6)).toEqual([]);
  });

  it.each(GUARDED_FILES)('%s：剥注释后没有 fontSize: 10..13', (rel) => {
    const source = fs.readFileSync(path.join(SRC, rel), 'utf8');
    expect(offenders(source, SMALL_FONT)).toEqual([]);
  });
});
