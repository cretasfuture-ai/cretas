type Parameters = Record<string, unknown> | readonly unknown[];
const catalogs = import.meta.glob<Record<string, string>>('../locales/zh-CN/*.json', { eager: true, import: 'default' });
const messages = Object.assign({}, ...Object.values(catalogs)) as Record<string, string>;

export function useT() {
  function t(key: string, values?: Parameters): string {
    const text = messages[key] ?? key;
    if (!values) return text;
    return text.replace(/\{(\w+)\}/g, (match, name: string) => {
      const value = Array.isArray(values) ? values[Number(name)] : (values as Record<string, unknown>)[name];
      return value == null ? match : String(value);
    });
  }
  return { t };
}
