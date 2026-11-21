import { readdir, readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { transform } from './transform.mjs';

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: { 'dry-run': { type: 'boolean' }, report: { type: 'string' }, list: { type: 'string' },
    'source-root': { type: 'string' }, 'element-only': { type: 'boolean' } },
});
if ((values['source-root'] || values['element-only']) && !values['dry-run']) throw new Error('--source-root 和 --element-only 仅用于只读统计');
const root = values['source-root'] ? resolve(values['source-root']) : fileURLToPath(new URL('../../../', import.meta.url));
const src = resolve(root, 'src');
const views = resolve(src, 'views');

async function vueFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(entry => {
    const name = resolve(directory, entry.name);
    if (entry.isDirectory()) return entry.name === '__tests__' ? [] : vueFiles(name);
    return entry.isFile() && entry.name.endsWith('.vue') ? [name] : [];
  }));
  return nested.flat();
}

const listed = values.list ? JSON.parse(await readFile(values.list, 'utf8')) : [];
if (!Array.isArray(listed) || listed.some(file => typeof file !== 'string' && typeof file?.file !== 'string')) throw new Error('--list 须为 Vue 文件路径数组或包含 file 的页面对象数组');
const requested = [...positionals, ...listed.map(file => typeof file === 'string' ? file : file.file)];
if (!values['dry-run'] && !requested.length) throw new Error('写入模式须显式指定文件或 --list');
const files = requested.length ? requested.map(file => resolve(root, file)) : await vueFiles(src);
if (new Set(files).size !== files.length) throw new Error('文件列表含重复路径');
const summary = {
  scannedFiles: files.length, elementFiles: 0, mixedFiles: 0, anyConvertedFiles: 0, fullyConvertedFiles: 0,
  tags: 0, converted: 0, translated: 0, untranslated: 0, keys: 0, todoCount: 0, byTag: {}, reasons: {}, failures: [],
};
const report = { summary, files: [] };
const changed = [];
const catalogs = new Map();
const increment = (object, key, count = 1) => { object[key] = (object[key] ?? 0) + count; };
const pathName = file => relative(root, file).split(sep).join('/');
const sum = object => Object.values(object).reduce((total, count) => total + count, 0);
for (const file of files.sort()) {
  const local = relative(values['dry-run'] ? src : views, file);
  if (!local || local.startsWith('..') || isAbsolute(local) || !file.endsWith('.vue')) {
    throw new Error(`文件超出允许范围 ${pathName(file)}`);
  }
  const source = await readFile(file, 'utf8');
  const name = pathName(file);
  try {
    const pass = transform(source, name);
    if (!values['dry-run'] && transform(pass.code, name).code !== pass.code) throw new Error('二次转换改变了源码');
    const { code, catalog, ...entry } = pass;
    const tagCount = sum(entry.tags);
    if (values['element-only'] && !tagCount) continue;
    const convertedCount = sum(entry.converted);
    if (tagCount) {
      summary.elementFiles++;
      summary.mixedFiles += Number(entry.nuxtTags > 0);
      summary.anyConvertedFiles += Number(convertedCount > 0);
      summary.fullyConvertedFiles += Number(convertedCount === tagCount);
    }
    summary.tags += tagCount;
    summary.converted += convertedCount;
    summary.translated += entry.translated;
    summary.untranslated += entry.untranslated;
    summary.todoCount += entry.todos.length;
    for (const [tag, count] of Object.entries(entry.tags)) {
      const row = summary.byTag[tag] ??= { tags: 0, converted: 0 };
      row.tags += count;
      row.converted += entry.converted[tag] ?? 0;
    }
    entry.todos.forEach(todo => increment(summary.reasons, todo.reason));
    if (tagCount || entry.translated || entry.todos.length) report.files.push({ file: name, ...entry, keys: Object.keys(catalog) });
    if (code !== source) changed.push({ file, code });
    for (const [key, text] of Object.entries(catalog)) {
      const module = key.split('.')[0];
      const messages = catalogs.get(module) ?? new Map();
      if (messages.has(key) && messages.get(key) !== text) throw new Error(`文案 key 冲突 ${key}`);
      messages.set(key, text);
      catalogs.set(module, messages);
    }
  } catch (error) {
    summary.failures.push({ file: name, error: error.message });
  }
}
summary.keys = [...catalogs.values()].reduce((count, catalog) => count + catalog.size, 0);
if (values.report) {
  await mkdir(dirname(resolve(values.report)), { recursive: true });
  await writeFile(values.report, JSON.stringify(report, null, 2) + '\n');
} else {
  console.log(JSON.stringify(report, null, 2));
}
if (summary.failures.length) process.exitCode = 2;
else if (!values['dry-run']) {
  const directory = resolve(src, 'locales', 'zh-CN');
  const outputs = [];
  for (const [module, entries] of catalogs) {
    const file = resolve(directory, `${module}.json`);
    const existing = await readFile(file, 'utf8').then(JSON.parse).catch(error => {
      if (error.code === 'ENOENT') return {};
      throw error;
    });
    for (const [key, text] of entries) {
      if (Object.hasOwn(existing, key) && existing[key] !== text) throw new Error(`已存在不同文案 ${key}`);
      existing[key] = text;
    }
    outputs.push({ file, code: JSON.stringify(Object.fromEntries(Object.entries(existing).sort(([a], [b]) => a.localeCompare(b))), null, 2) + '\n' });
  }
  await mkdir(directory, { recursive: true });
  for (const { file, code } of [...outputs, ...changed]) await writeFile(file, code);
}
