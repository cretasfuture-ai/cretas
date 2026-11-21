import { useT } from './useT';

/** Resolve actual page references when source tests assert specific UI copy. */
export function sourceWithChinese(source: string): string {
  const { t } = useT();
  return source
    .replace(/\{\{\s*t\('([\w.]+)'\)\s*\}\}/g, (_, key: string) => t(key))
    .replace(/:([\w-]+)="t\('([\w.]+)'\)"/g, (_, attribute: string, key: string) => `${attribute}="${t(key)}"`)
    .replace(/\bt\('([\w.]+)'\)/g, (_, key: string) => `'${t(key)}'`);
}
