import { createHash } from 'node:crypto';
import { babelParse, parse } from 'vue/compiler-sfc';
import { mappings, safeEvents, sharedAttributes } from './mappings.mjs';

const han = /\p{Script=Han}/u;
const uiAttributes = new Set(['title', 'label', 'placeholder', 'description', 'empty-text', 'start-placeholder', 'end-placeholder', 'aria-label', 'alt']);
const uiProperties = new Set(['title', 'label', 'placeholder', 'description', 'message', 'confirmButtonText', 'cancelButtonText', 'emptyText']);
const messages = new Set(['ElMessage', 'ElMessageBox', 'ElNotification']);
const compatImport = '@/components/compat';

function applyEdits(source, edits) {
  let end = source.length;
  let result = '';
  for (const edit of [...edits].sort((a, b) => b.start - a.start || b.end - a.end)) {
    if (edit.end > end) throw new Error('AST edits overlap');
    result = edit.value + source.slice(edit.end, end) + result;
    end = edit.start;
  }
  return source.slice(0, end) + result;
}

function visitScript(node, callback, parent) {
  if (!node || typeof node !== 'object') return;
  if (typeof node.type === 'string') callback(node, parent);
  for (const [key, child] of Object.entries(node)) {
    if (['loc', 'extra', 'comments', 'tokens'].includes(key) || key.endsWith('Comments')) continue;
    if (Array.isArray(child)) child.forEach(item => visitScript(item, callback, node));
    else visitScript(child, callback, node);
  }
}

function staticName(prop) {
  if (prop.type === 6) return prop.name;
  return prop.arg?.isStatic ? prop.arg.content : undefined;
}

function componentIssue(node, spec, parent) {
  const tag = node.tag;
  if (tag === 'el-table-column' && parent?.tag !== 'el-table') return '表格列不在直接父表格下';
  if (tag === 'el-descriptions-item' && parent?.tag !== 'el-descriptions') return '详情项不在直接父详情下';
  if (tag === 'el-card' && node.isSelfClosing && node.props.some(prop => staticName(prop) === 'header')) return '自闭合卡片标题';
  if (tag === 'el-option' && parent?.tag !== 'el-select') return '选项不在直接父选择器下';
  if (tag === 'el-option' && node.children.some(child => child.type !== 2 || child.content.trim())) return '选项内容插槽需要适配';
  if (tag === 'el-radio' && !node.props.some(prop => prop.type === 7 && prop.name === 'model')) return '单选组需要整组适配';
  if (tag === 'el-checkbox' && parent?.tag === 'el-checkbox-group') return '复选组需要整组适配';
  if (tag === 'el-tag' && node.props.some(prop => staticName(prop) === 'round')) return '圆角标签';
  if (tag === 'el-input' && node.props.some(prop => staticName(prop) === 'clearable')) return '输入框清除按钮';
  if (tag === 'el-button' && node.props.filter(prop => ['plain', 'link', 'text'].includes(staticName(prop))).length > 1) return '按钮样式需合并';
  if (tag === 'el-progress' && node.children.some(child => child.type !== 2 || child.content.trim())) return '进度内容插槽需要适配';
  for (const prop of node.props) {
    const name = staticName(prop);
    if (prop.type === 7) {
      if (['if', 'else', 'else-if', 'for', 'show', 'once', 'memo'].includes(prop.name)) continue;
      if (prop.name === 'loading' && spec.component.startsWith('Compat')) continue;
      if (prop.name === 'model' && !prop.modifiers?.length
        && (!prop.arg && ['el-input', 'el-switch', 'el-checkbox', 'el-select', 'el-radio', 'el-date-picker', 'el-dialog'].includes(tag)
          || tag === 'el-pagination' && ['current-page', 'page-size'].includes(name))) continue;
      if (prop.name === 'on' && safeEvents[tag]?.has(name)) continue;
      if (prop.name !== 'bind' || !name || prop.modifiers?.length) return '事件、指令或动态属性需要核对';
      if (Object.hasOwn(spec.values, name) || spec.rename[name] === null) return `动态属性 ${name} 需要值映射`;
    }
    if (!sharedAttributes.has(name) && !spec.props.has(name) && !name?.startsWith('aria-') && !name?.startsWith('data-')) {
      return `属性 ${name ?? '?'} 需要适配`;
    }
    if (prop.type === 6 && Object.hasOwn(spec.values, name) && !Object.hasOwn(spec.values[name], prop.value?.content ?? '')) {
      return `属性 ${name} 的值需要适配`;
    }
    if (prop.type === 6 && tag === 'el-progress' && name === 'percentage' && !/^\d+(\.\d+)?$/.test(prop.value?.content ?? '')) {
      return '进度百分比需要数字';
    }
    if (spec.rename[name] === null && !(tag === 'el-card' && ['header', 'shadow'].includes(name))) {
      return `属性 ${name} 需要适配`;
    }
  }
  const slots = node.children
    .flatMap(child => child.props ?? [])
    .filter(prop => prop.type === 7 && prop.name === 'slot');
  const compatSlots = {
    'el-table': ['default', 'empty'], 'el-table-column': ['default', 'header'],
    'el-form-item': ['label'], 'el-dialog': ['default', 'header', 'footer'],
    'el-skeleton': ['template', 'default'],
    'el-descriptions': ['title', 'extra'], 'el-descriptions-item': ['label', 'default'],
  };
  if (slots.some(slot => !(compatSlots[tag] ?? (tag === 'el-card' ? ['header', 'footer'] : [])).includes(staticName(slot)))) {
    return '具名插槽需要适配';
  }
  if (tag === 'el-alert' && node.children.some(child => child.type !== 2 || child.content.trim())) {
    return '提示内容插槽需要适配';
  }
}

export function transform(source, file, { translate = true } = {}) {
  const { descriptor, errors } = parse(source, { filename: file });
  if (errors.length) throw new Error(errors.map(error => error.message ?? error).join('\n'));
  const edits = [];
  const todos = [];
  const converted = {};
  const tags = {};
  const catalog = {};
  const compatibility = new Set();
  let nuxtTags = 0;
  const [module = 'views', ...tail] = ('/' + file.replaceAll('\\', '/')).split('/src/views/').at(-1).split('/');
  const slug = value => value.replace(/[^a-zA-Z0-9_]+/g, '_').toLowerCase();
  const namespace = [slug(module), tail.map(slug).join('_').replace(/_vue$/, '') || 'index'].join('.');
  const setup = descriptor.scriptSetup;
  const script = setup && !setup.src ? setup : undefined;
  const ast = script && babelParse(script.content, {
    sourceType: 'module',
    plugins: [...(script.lang?.startsWith('ts') ? ['typescript'] : []), ...(script.lang?.endsWith('x') ? ['jsx'] : [])],
  });
  const occupied = new Set();
  const messageBindings = new Set();
  visitScript(ast, node => { if (node.type === 'Identifier') occupied.add(node.name); });
  for (const declaration of ast?.program.body ?? []) {
    if (declaration.type !== 'ImportDeclaration' || declaration.source.value !== 'element-plus') continue;
    for (const specifier of declaration.specifiers) {
      if (specifier.type === 'ImportSpecifier' && messages.has(specifier.imported.name)) messageBindings.add(specifier.local.name);
    }
  }
  const hasTranslator = ast?.program.body.some(node => node.type === 'ImportDeclaration'
    && node.source.value === '@/i18n-pilot/useT');
  const canTranslate = Boolean(translate && ast && (!occupied.has('t') || hasTranslator));
  let translated = 0;
  let untranslated = 0;
  const todo = (reason, line, tag) => todos.push({ line, ...(tag ? { tag } : {}), reason });
  const crlf = source.includes('\r\n') && !source.replaceAll('\r\n', '').includes('\n');
  const add = (start, end, value) => edits.push({ start, end, value: crlf ? value.replace(/\r?\n/g, '\r\n') : value });
  const keyFor = (text, semantic) => {
    const digest = createHash('sha256').update(text).digest('hex').slice(0, 12);
    const key = `${namespace}.${slug(semantic)}_${digest}`;
    catalog[key] = text;
    translated++;
    return key;
  };
  const expression = (text, semantic) => `t('${keyFor(text, semantic)}')`;
  const template = descriptor.template;
  const offset = 0; // compiler-sfc template AST offsets are relative to the whole SFC.
  const put = (loc, value) => add(offset + loc.start.offset, offset + loc.end.offset, value);
  function localize(text, semantic, loc, makeValue) {
    if (!han.test(text)) return;
    if (canTranslate) put(loc, makeValue(expression(text, semantic)));
    else {
      untranslated++;
      todo('中文文案需要可用的 script setup 翻译绑定', loc.start.line);
    }
  }
  function localizeExpression(node, semantic, line, singleQuoted = false) {
    if (!node || !han.test(node.content) || !translate) return;
    const source = node.loc.source;
    let literals = 0;
    const read = expressionNode => {
      if (expressionNode.type === 'ConditionalExpression') {
        read(expressionNode.consequent);
        read(expressionNode.alternate);
      } else if (expressionNode.type === 'LogicalExpression' && ['||', '??'].includes(expressionNode.operator)) {
        read(expressionNode.right);
      } else {
        const text = expressionNode.type === 'StringLiteral' ? expressionNode.value
          : expressionNode.type === 'TemplateLiteral' && !expressionNode.expressions.length ? expressionNode.quasis[0].value.cooked : '';
        if (!han.test(text) || !canTranslate) return;
        literals++;
        const call = expression(text, semantic);
        add(node.loc.start.offset + expressionNode.start - 1, node.loc.start.offset + expressionNode.end - 1,
          singleQuoted ? call.replaceAll("'", '&apos;') : call);
      }
    };
    let parsed;
    try { parsed = babelParse(`(${source})`, { plugins: ['typescript'] }).program.body[0]; }
    catch { /* Vue can use expressions outside Babel's expression grammar. */ }
    if (parsed?.type === 'ExpressionStatement') read(parsed.expression);
    if (!literals) {
      untranslated++;
      todo('模板动态表达式含中文，需要确认业务含义', line);
    }
  }
  const families = {
    'el-table': ['el-table-column'], 'el-descriptions': ['el-descriptions-item'],
    'el-select': ['el-option'], 'el-form': ['el-form-item'],
  };
  const componentIssues = new WeakMap();
  function examine(node, parent) {
    if (node.type !== 1) return;
    const spec = mappings[node.tag];
    const own = !spec ? '组件暂无映射'
      : spec.component.startsWith('Compat') && (!ast || occupied.has(spec.component)) ? '适配组件需要可用的 script setup 导入'
        : componentIssue(node, spec, parent);
    if (own) componentIssues.set(node, own);
    for (const child of Array.isArray(node.children) ? node.children : []) examine(child, node);
    const grouped = families[node.tag];
    if (!grouped) return;
    const groupChildren = element => element.children.flatMap(child => {
      if (child.type !== 1 || child.tag === node.tag) return [];
      if (grouped.includes(child.tag)) return [child];
      return node.tag === 'el-form' || child.tag === 'template' ? groupChildren(child) : [];
    });
    const children = groupChildren(node);
    const unsupportedKind = children.find(child => child.tag === 'el-table-column' && child.props.some(prop =>
      staticName(prop) === 'type' && (prop.type !== 6 || !['default', 'selection', 'index'].includes(prop.value?.content))));
    const unknownChild = node.tag !== 'el-form' && node.children.some(child => child.type === 1 && !grouped.includes(child.tag) && child.tag !== 'template');
    if (componentIssues.has(node) || children.some(child => componentIssues.has(child)) || unsupportedKind || unknownChild) {
      if (!componentIssues.has(node)) componentIssues.set(node, '父子组件需要整组迁移');
      for (const child of children) if (!componentIssues.has(child)) componentIssues.set(child, '父子组件需要整组迁移');
    }
  }
  for (const child of template?.ast?.children ?? []) examine(child, template.ast);
  function walk(node, parent) {
    const line = node.loc?.start.line ?? 1;
    if (node.type === 2 && parent?.tag !== 'pre' && parent?.tag !== 'textarea') {
      const raw = node.loc.source;
      if (han.test(node.content)) {
        localize(node.content.trim(), parent?.tag === 'el-button' || parent?.tag === 'UButton' ? 'action' : 'text', node.loc,
          value => raw.slice(0, raw.length - raw.trimStart().length) + '{{ ' + value + ' }}' + raw.slice(raw.trimEnd().length));
      }
    }
    if (node.type === 5) localizeExpression(node.content, 'text', line);
    if (node.type === 1) {
      const tag = node.tag;
      if (/^U[A-Z]/.test(tag) || /^u-/.test(tag)) nuxtTags++;
      const spec = mappings[tag];
      const isElement = tag.startsWith('el-');
      if (isElement) tags[tag] = (tags[tag] ?? 0) + 1;
      const issue = isElement ? componentIssues.get(node) : undefined;
      const replacement = isElement && !issue ? spec : undefined;
      if (issue) todo(issue, line, tag);
      if (replacement) {
        const start = offset + node.loc.start.offset;
        const endTag = node.loc.source.lastIndexOf(`</${tag}`);
        add(start + 1, start + 1 + tag.length, spec.component);
        if (!node.isSelfClosing && endTag > 0) add(start + endTag + 2, start + endTag + 2 + tag.length, spec.component);
        if (spec.component.startsWith('Compat')) compatibility.add(spec.component);
        converted[tag] = (converted[tag] ?? 0) + 1;
        const names = new Set(node.props.map(staticName));
        const defaults = [];
        if (tag === 'el-button' && !names.has('type')) defaults.push('color="neutral"');
        if (tag === 'el-button' && !['type', 'plain', 'link', 'text'].some(name => names.has(name))) defaults.push('variant="outline"');
        if (tag === 'el-tag' && !names.has('effect')) defaults.push('variant="soft"');
        if (tag === 'el-progress' && !names.has('show-text')) defaults.push('status');
        if (defaults.length) add(start + 1 + tag.length, start + 1 + tag.length, ' ' + defaults.join(' '));
      }
      for (const prop of node.props) {
        const name = staticName(prop);
        if (name === 'label' && ['el-radio', 'el-radio-button', 'el-checkbox', 'el-checkbox-button'].includes(tag)
          && !node.props.some(attr => staticName(attr) === 'value')
          && han.test(prop.value?.content ?? prop.exp?.content ?? '') && translate) {
          untranslated++;
          todo('label 同时充当选项业务值', line);
          continue;
        }
        const rename = replacement && Object.hasOwn(spec.rename, name) ? spec.rename[name] : name;
        if (replacement && rename === null) {
          if (tag === 'el-card' && name === 'header') {
            const text = prop.value?.content ?? '';
            const body = han.test(text) && canTranslate ? `{{ ${expression(text, 'header')} }}` : text.replaceAll('&', '&amp;').replaceAll('<', '&lt;');
            const first = node.children[0]?.loc.start.offset ?? node.loc.start.offset + node.loc.source.lastIndexOf('</');
            add(offset + first, offset + first, `<template #header>${body}</template>`);
          }
          put(prop.loc, '');
          continue;
        }
        if (prop.type === 6 && prop.value && uiAttributes.has(name) && han.test(prop.value.content)) {
          localize(prop.value.content, name, prop.loc, value => `:${rename}="${value}"`);
          continue;
        }
        if (prop.type === 7 && prop.exp && uiAttributes.has(name)) {
          const head = prop.loc.source.slice(0, prop.exp.loc.start.offset - prop.loc.start.offset);
          localizeExpression(prop.exp, name, line, head.trimEnd().endsWith("'"));
        }
        if (replacement && prop.type === 6 && Object.hasOwn(spec.values, name)) {
          put(prop.loc, `${rename}="${spec.values[name][prop.value?.content ?? '']}"`);
        } else if (replacement && prop.type === 6 && tag === 'el-progress' && name === 'percentage') {
          put(prop.loc, `:${rename}="${Number(prop.value.content)}"`);
        } else if (replacement && rename !== name) {
          if (prop.type === 6) put(prop.loc, rename + prop.loc.source.slice(name.length));
          if (prop.type === 7 && prop.arg) put(prop.arg.loc, rename);
        }
      }
      if (replacement && tag === 'el-checkbox' && node.children.length && !node.isSelfClosing) {
        const first = node.children[0].loc.start.offset;
        const last = node.children.at(-1).loc.end.offset;
        add(first, first, '<template #label>');
        add(last, last, '</template>');
      }
    }
    for (const child of Array.isArray(node.children) ? node.children : []) walk(child, node);
  }
  if (template?.ast) walk(template.ast);

  const imports = [];
  if (compatibility.size) imports.push(`import { ${[...compatibility].sort().join(', ')} } from '${compatImport}';`);
  const scriptOffset = script?.loc.start.offset ?? 0;
  const migratedMessages = new Map();
  visitScript(ast, (node, parent) => {
    const putScript = (start, end, value) => add(scriptOffset + start, scriptOffset + end, value);
    if (node.type === 'ImportDeclaration' && node.source.value === 'element-plus') {
      const names = node.specifiers.filter(spec => spec.type === 'ImportSpecifier' && spec.importKind !== 'type' && messages.has(spec.imported.name));
      if (names.length) {
        const others = node.specifiers.filter(spec => !names.includes(spec));
        if (others.some(spec => spec.type !== 'ImportSpecifier') || node.importKind === 'type') {
          todo('混合消息导入需人工拆分', node.loc.start.line + script.loc.start.line - 1);
          return;
        }
        const specifiers = group => group.map(spec => script.content.slice(spec.start, spec.end)).join(', ');
        if (occupied.has('useCompatMessages')) {
          todo('消息适配导入发生命名冲突', node.loc.start.line + script.loc.start.line - 1);
          return;
        }
        for (const spec of names) migratedMessages.set(spec.local.name, spec.imported.name);
        putScript(node.start, node.end, others.length ? `import { ${specifiers(others)} } from 'element-plus';` : '');
      }
    }
    if (node.type !== 'StringLiteral' && node.type !== 'TemplateLiteral') return;
    const text = node.type === 'StringLiteral' ? node.value : node.quasis.map(part => part.value.cooked).join('');
    if (!han.test(text)) return;
    const property = parent?.type === 'ObjectProperty' && parent.value === node ? parent.key.name ?? parent.key.value : undefined;
    const receiver = parent?.callee?.object?.name ?? parent?.callee?.name;
    const messageArgument = parent?.type === 'CallExpression' && parent.arguments.indexOf(node) >= 0
      && parent.arguments.indexOf(node) < 2 && messageBindings.has(receiver);
    const semantic = uiProperties.has(property) ? property : messageArgument ? 'message' : undefined;
    if (canTranslate && semantic && (node.type === 'StringLiteral' || node.expressions.length === 0)) {
      putScript(node.start, node.end, expression(text, semantic));
    } else if (translate) {
      untranslated++;
      todo('script 中文字面量需要区分界面与业务数据', node.loc.start.line + script.loc.start.line - 1);
    }
  });
  if (migratedMessages.size) {
    const names = [...migratedMessages].map(([local, exported]) => local === exported ? local : `${exported}: ${local}`);
    imports.push(`import { useCompatMessages } from '${compatImport}/messages';`, `const { ${names.join(', ')} } = useCompatMessages();`);
  }
  if (translated && !hasTranslator) imports.push("import { useT } from '@/i18n-pilot/useT';", 'const { t } = useT();');
  if (imports.length) add(scriptOffset, scriptOffset, '\n' + imports.join('\n') + '\n');
  return { code: applyEdits(source, edits), tags, nuxtTags, converted, todos, catalog, translated, untranslated };
}
