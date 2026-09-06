/**
 * 闸: CustomerManagementScreen 里每个字面 `t('…')` 键在 zh-CN / en-US 资源里都存在。
 *
 * 2026-09-06 真机 (lsm004) 实拍两处 i18n 键泄漏到界面: 创建弹层标题显示
 * `customerManagement.modal.createTitle`, 校验框标题显示 `common.hint` —— 该屏 `useTranslation('management')`,
 * 前者 management.json 里没有 modal 节, 后者写成 `common.hint` 在 management 命名空间里找 (要写 `common:hint`)。
 *
 * 判据: 解析源码里的字面键 (含 `ns:key` 形式), 按该屏的默认命名空间在资源 JSON 里逐个取值; 缺一个就红,
 * 并把缺的键原样打出来。带 `defaultValue` 的调用也照查 (defaultValue 是兜底, 不是许可)。
 * 阳性前提: 至少解析到 20 个键 (解析器打空与「全都有」长得一样)。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.resolve(__dirname, '../..');
const SCREEN = path.join(SRC, 'screens/management/CustomerManagementScreen.tsx');
const DEFAULT_NS = 'management';

function loadNs(locale: string, ns: string): Record<string, unknown> {
  return JSON.parse(fs.readFileSync(path.join(SRC, 'i18n/locales', locale, `${ns}.json`), 'utf8'));
}

function lookup(obj: Record<string, unknown>, dotted: string): unknown {
  return dotted.split('.').reduce<unknown>((acc, k) => (acc && typeof acc === 'object' ? (acc as Record<string, unknown>)[k] : undefined), obj);
}

function literalKeys(source: string): string[] {
  const code = source.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^\s*\/\/.*$/gm, ' ');
  return [...code.matchAll(/\bt\(\s*'([^']+)'/g)].map((m) => m[1] ?? '').filter((k) => k.length > 0);
}

describe('CustomerManagementScreen i18n keys exist', () => {
  const keys = [...new Set(literalKeys(fs.readFileSync(SCREEN, 'utf8')))];

  it('阳性前提: 解析到足够多的字面键', () => {
    expect(keys.length).toBeGreaterThan(20);
  });

  it.each(['zh-CN', 'en-US'])('%s: 每个键都能取到字符串', (locale) => {
    const cache = new Map<string, Record<string, unknown>>();
    const missing: string[] = [];
    for (const raw of keys) {
      const sep = raw.indexOf(':');
      const ns = sep >= 0 ? raw.slice(0, sep) : DEFAULT_NS;
      const key = sep >= 0 ? raw.slice(sep + 1) : raw;
      if (!cache.has(ns)) cache.set(ns, loadNs(locale, ns));
      const v = lookup(cache.get(ns) ?? {}, key);
      if (typeof v !== 'string') missing.push(raw);
    }
    expect(missing.join(', ')).toBe('');
  });

  it('实拍泄漏的两个键现在有值 (阴性对照: 一个明显不存在的键仍然取不到)', () => {
    const zhM = loadNs('zh-CN', 'management');
    const zhC = loadNs('zh-CN', 'common');
    expect(lookup(zhM, 'customerManagement.modal.createTitle')).toBe('新建客户');
    expect(lookup(zhM, 'customerManagement.modal.editTitle')).toBe('编辑客户');
    expect(lookup(zhC, 'hint')).toBe('提示');
    expect(lookup(zhM, 'common.hint')).toBeUndefined();   // 原来的错误写法在 management 里确实取不到
    expect(lookup(zhM, 'customerManagement.modal.nope')).toBeUndefined();
  });
});
