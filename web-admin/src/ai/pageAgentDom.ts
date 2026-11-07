/**
 * Narrow, page-local DOM bridge for the global AI agent.
 *
 * This module deliberately knows nothing about business APIs or a particular
 * tenant/demo.  The caller supplies the already-authorized routes and keeps
 * the snapshot's lifetime.  A snapshot is therefore both the observable
 * surface and the capability boundary for an action.
 */

export type AgentActionKind = 'click' | 'fill' | 'navigate' | 'wait' | 'ask' | 'done';

export interface AgentAction {
  kind: AgentActionKind;
  target?: string;
  value?: string;
  message: string;
}

export interface AgentRoute {
  path: string;
  title: string;
}

export interface AgentControlSnapshot {
  ref: string;
  role: string;
  label: string;
  value?: string;
  required?: boolean;
  inputType?: string;
}

export interface AgentRouteSnapshot {
  ref: string;
  path: string;
  title: string;
}

export interface AgentPageSnapshot {
  title: string;
  text: string;
  controls: AgentControlSnapshot[];
  routes: AgentRouteSnapshot[];
}

export interface AgentSnapshot {
  id: string;
  route: string;
  page: AgentPageSnapshot;
  /** Runtime-only element capabilities; do not serialize. */
  targets: ReadonlyMap<string, HTMLElement>;
  /** Runtime-only authorized route capabilities; do not serialize. */
  routeTargets: ReadonlyMap<string, AgentRoute>;
}

export interface ObserveAgentPageOptions {
  document: Document;
  route: string;
  routes: readonly AgentRoute[];
  title: string;
}

export interface ExecuteAgentActionOptions {
  assertCurrent: () => void;
  beforeAction?: (element: HTMLElement) => Promise<void>;
}

const MAX_PAGE_TEXT = 6000;
const MAX_LABEL = 180;
const MAX_VALUE = 240;
const MAX_MESSAGE = 1000;

const EXCLUDED_SELECTOR = [
  '[data-ai-page-agent]',
  '[data-native-demo-toolbar]',
  '.ai-entry-drawer',
  'iframe',
  '[data-workspace-reference]',
  '[data-reference-frame]',
  '.reference-pane',
  '.reference-frame-wrap',
  '.reference-frame-content',
  '.readonly-shield',
].join(',');

const SENSITIVE_RE = /password|passwd|token|secret|api[\s_-]*key|access[\s_-]*key|验证码|密码|口令|令牌|密钥|秘钥|私钥|安全码|授权码/i;
const DESTRUCTIVE_RE = /删除|删掉|作废|付款|支付|退款|权限配置|清空|清除全部|销毁|退出登录|退出账号|退出账户|退出全屏|退出|登出|注销|切换工厂|切换账号|切换账户|delete|remove|void|refund|pay(?:ment)?|permission|clear|logout|log\s*out|sign\s*out|switch\s+(?:factory|account)/i;
const SECONDARY_AI_RE = /(?:ai|智能)\s*(?:录入|填写|填入|输入)|填入表单/i;
const UNSAFE_SCHEME_RE = /^(?:javascript|data|vbscript):/i;

interface ControlSignature {
  tag: string;
  role: string;
  type: string;
  id: string;
  name: string;
  stableLabel: string;
  displaySignature: string;
  rowIdentity: string;
  value: string;
  checked: boolean;
  required: boolean;
}

interface SnapshotState {
  signatures: ReadonlyMap<string, ControlSignature>;
}

const snapshotState = new WeakMap<AgentSnapshot, SnapshotState>();
let snapshotSequence = 0;

function truncate(value: string, max: number): string {
  const normalized = value.replace(/\s+/g, ' ').trim();
  return normalized.length > max ? `${normalized.slice(0, max - 1)}…` : normalized;
}

function textOf(element: Element): string {
  const candidate = 'innerText' in element
    ? String((element as HTMLElement).innerText || '')
    : String(element.textContent || '');
  return truncate(candidate, MAX_LABEL);
}

function hasSensitiveText(value: string): boolean {
  return SENSITIVE_RE.test(value);
}

function isExcluded(element: Element): boolean {
  return Boolean(element.closest(EXCLUDED_SELECTOR));
}

function computedStyleFor(element: Element): CSSStyleDeclaration | null {
  const view = element.ownerDocument.defaultView;
  return view?.getComputedStyle ? view.getComputedStyle(element) : null;
}

function isVisible(element: HTMLElement): boolean {
  if (!element.isConnected || element.hasAttribute('hidden')
    || element.getAttribute('aria-hidden') === 'true' || isExcluded(element)) return false;

  let current: HTMLElement | null = element;
  while (current) {
    const style = computedStyleFor(current);
    if (style && (style.display === 'none' || style.visibility === 'hidden'
      || style.visibility === 'collapse' || style.opacity === '0')) return false;
    if (current.hasAttribute('hidden') || current.getAttribute('aria-hidden') === 'true') return false;
    current = current.parentElement;
  }

  // jsdom has no layout boxes.  CSS visibility above is still meaningful in
  // tests, while a real browser additionally gets this geometry check.
  const rects = element.getClientRects();
  if (rects.length > 0) return true;
  return !element.ownerDocument.defaultView || element.ownerDocument.defaultView.navigator.userAgent.includes('jsdom');
}

function isTopmostAtCenter(element: HTMLElement): boolean {
  const doc = element.ownerDocument;
  if (typeof doc.elementFromPoint !== 'function') return true;
  const rect = element.getBoundingClientRect();
  // A layout-less test DOM has no meaningful center; visibility is the only
  // available signal there. Real browser elements have a non-zero box.
  if (rect.width <= 0 || rect.height <= 0) return true;
  const hit = doc.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
  return hit === element || (hit !== null && element.contains(hit));
}

function isDisabled(element: HTMLElement): boolean {
  if (element.getAttribute('aria-disabled') === 'true' || element.classList.contains('is-disabled')) return true;
  if (element.closest('fieldset[disabled]')) return true;
  if (element instanceof HTMLButtonElement || element instanceof HTMLInputElement
    || element instanceof HTMLTextAreaElement || element instanceof HTMLSelectElement) {
    return element.disabled;
  }
  return false;
}

function safeHref(element: HTMLElement): boolean {
  if (!(element instanceof HTMLAnchorElement)) return true;
  const href = element.getAttribute('href')?.trim() || '';
  if (!href || UNSAFE_SCHEME_RE.test(href)) return false;
  if (href.startsWith('//')) return false;
  try {
    const url = new URL(href, element.ownerDocument.baseURI || undefined);
    const origin = element.ownerDocument.defaultView?.location.origin;
    return !origin || url.origin === origin;
  } catch {
    return false;
  }
}

function attributeText(element: HTMLElement, name: string): string {
  return element.getAttribute(name)?.trim() || '';
}

function associatedLabel(element: HTMLElement): string {
  const withRowSummary = (label: string): string => {
    const summary = tableRowSummaryFor(element);
    return summary ? `${label} · ${summary}` : label;
  };
  const ariaLabel = attributeText(element, 'aria-label');
  if (ariaLabel) return withRowSummary(ariaLabel);

  const labelledBy = attributeText(element, 'aria-labelledby');
  if (labelledBy) {
    const labels = labelledBy.split(/\s+/)
      .map((id) => element.ownerDocument.getElementById(id))
      .filter((node): node is HTMLElement => Boolean(node))
      .map(textOf)
      .filter(Boolean);
    if (labels.length) return withRowSummary(labels.join(' '));
  }

  const id = attributeText(element, 'id');
  if (id) {
    const explicit = Array.from(element.ownerDocument.querySelectorAll('label[for]'))
      .find((label) => label.getAttribute('for') === id);
    if (explicit) return withRowSummary(textOf(explicit));
  }

  const parentLabel = element.closest('label');
  if (parentLabel) return withRowSummary(textOf(parentLabel));

  // A table header identifies a column more precisely than a broad
  // el-form-item label. Row identity is appended above for every table label.
  const tableHeader = tableHeaderForElement(element);
  if (tableHeader) return withRowSummary(tableHeader);

  const formItemLabel = element.closest('.el-form-item')?.querySelector('label');
  if (formItemLabel) return withRowSummary(textOf(formItemLabel));

  const placeholder = attributeText(element, 'placeholder');
  if (placeholder) return withRowSummary(placeholder);

  const title = attributeText(element, 'title');
  if (title) return withRowSummary(title);

  return withRowSummary(textOf(element));
}

function directSemanticLabel(element: HTMLElement): string {
  const ariaLabel = attributeText(element, 'aria-label');
  if (ariaLabel) return ariaLabel;
  const labelledBy = attributeText(element, 'aria-labelledby');
  if (labelledBy) {
    const labels = labelledBy.split(/\s+/)
      .map((id) => element.ownerDocument.getElementById(id))
      .filter((node): node is HTMLElement => Boolean(node))
      .map(textOf)
      .filter(Boolean);
    if (labels.length) return labels.join(' ');
  }
  const id = attributeText(element, 'id');
  if (id) {
    const explicit = Array.from(element.ownerDocument.querySelectorAll('label[for]'))
      .find((label) => label.getAttribute('for') === id);
    if (explicit) return textOf(explicit);
  }
  const parentLabel = element.closest('label');
  if (parentLabel) return textOf(parentLabel);
  const formItemLabel = element.closest('.el-form-item')?.querySelector('label');
  if (formItemLabel) return textOf(formItemLabel);
  const placeholder = attributeText(element, 'placeholder');
  if (placeholder) return placeholder;
  const title = attributeText(element, 'title');
  if (title) return title;
  return textOf(element);
}

function inputType(element: HTMLElement): string {
  if (element instanceof HTMLInputElement) return (element.type || 'text').toLowerCase();
  return element.getAttribute('type')?.toLowerCase() || '';
}

function readValue(element: HTMLElement): string | undefined {
  const raw = readRawValue(element);
  return raw === undefined ? undefined : truncate(raw, MAX_VALUE);
}

function readRawValue(element: HTMLElement): string | undefined {
  if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement
    || element instanceof HTMLSelectElement) return element.value;
  const role = element.getAttribute('role');
  if (role === 'combobox') {
    const input = element.querySelector<HTMLInputElement | HTMLTextAreaElement>('input, textarea');
    if (input) return input.value;
  }
  return undefined;
}

function tableCellFor(element: HTMLElement): HTMLElement | null {
  return element.closest<HTMLElement>('td, th')
    || element.closest<HTMLElement>('.el-table__cell, [role="cell"], [role="gridcell"]');
}

function rowCells(row: HTMLElement): HTMLElement[] {
  return Array.from(row.children).filter((child): child is HTMLElement => {
    const tag = child.tagName.toLowerCase();
    return tag === 'td' || tag === 'th' || child.classList.contains('el-table__cell')
      || child.getAttribute('role') === 'cell' || child.getAttribute('role') === 'gridcell'
      || child.getAttribute('role') === 'columnheader';
  });
}

function summaryCellText(cell: HTMLElement): string {
  return cellText(cell, MAX_LABEL);
}

function cellText(cell: HTMLElement, max?: number): string {
  const chunks: string[] = [];
  const visit = (node: Node): void => {
    if (node.nodeType === Node.TEXT_NODE) {
      const text = String(node.textContent || '').replace(/\s+/g, ' ').trim();
      if (text) chunks.push(text);
      return;
    }
    if (!(node instanceof HTMLElement) || !isVisible(node)) return;
    if (node !== cell && isPotentialControl(node)) {
      const value = readRawValue(node);
      if (value && !hasSensitiveText(value)) chunks.push(value);
      else if (node instanceof HTMLAnchorElement || node.getAttribute('role') === 'link') {
        const text = String(node.textContent || '').replace(/\s+/g, ' ').trim();
        if (text && !hasSensitiveText(text)) chunks.push(text);
      }
      return;
    }
    node.childNodes.forEach(visit);
  };
  visit(cell);
  const text = chunks.join(' ').replace(/\s+/g, ' ').trim();
  return max === undefined ? text : truncate(text, max);
}

const TABLE_IDENTITY_HEADER_RE = /单号|订单id|订单号|订单编号|采购id|采购单号|采购编号|销售id|销售单号|销售编号|计划id|计划号|计划编号|批次id|批次号|批次编号|物料id|物料编码|物料编号|sku|编号|编码|id$/i;

function tableForCell(cell: HTMLElement): HTMLElement | null {
  return cell.closest<HTMLElement>('.el-table')
    || cell.closest<HTMLElement>('table, [role="table"], [role="grid"]');
}

function bodyTableForCell(cell: HTMLElement): HTMLElement | null {
  return cell.closest<HTMLElement>('table') || tableForCell(cell);
}

function headerRowForTable(table: HTMLElement, row: HTMLElement): HTMLElement | undefined {
  return Array.from(table.querySelectorAll<HTMLElement>(
    'thead tr, .el-table__header-wrapper tr, [role="row"]',
  )).filter((candidate) => candidate !== row
    && (candidate.querySelector('[role="columnheader"]') || candidate.closest('thead'))).pop();
}

function productCellIdentity(cell: HTMLElement): string | undefined {
  const strong = cell.querySelector<HTMLElement>('strong');
  const muted = cell.querySelector<HTMLElement>('.muted');
  const name = strong ? cellText(strong) : '';
  const meta = muted ? cellText(muted) : '';
  const [codePart, ...specParts] = meta.split('·').map((part) => part.trim());
  const code = codePart && codePart !== '无编码' ? codePart : '';
  const specification = specParts.join(' · ').trim();
  if (code && !hasSensitiveText(code)) return `sku:${code}`;
  if (name && specification && specification !== '无规格'
    && !hasSensitiveText(`${name} ${specification}`)) {
    return `product:${name} · ${specification}`;
  }
  return undefined;
}

function tableRowIdentityFor(element: HTMLElement): string | undefined {
  const cell = tableCellFor(element);
  const row = cell?.closest<HTMLElement>('tr, [role="row"]');
  const table = cell ? tableForCell(cell) : null;
  if (!cell || !row || !table) return undefined;
  const cells = rowCells(row);
  const index = cells.indexOf(cell);
  if (index < 0) return undefined;
  const headerRow = headerRowForTable(table, row);
  if (!headerRow) return undefined;
  const headers = rowCells(headerRow);
  const keys: string[] = [];
  cells.forEach((candidate, candidateIndex) => {
    const header = headers[candidateIndex];
    const headerText = header ? cellText(header) : '';
    const productIdentity = /产品|商品/i.test(headerText)
      ? productCellIdentity(candidate)
      : undefined;
    if (!TABLE_IDENTITY_HEADER_RE.test(headerText) && !productIdentity) return;
    const value = productIdentity || cellText(candidate);
    if (!value || hasSensitiveText(`${headerText} ${value}`)) return;
    keys.push(`${headerText}=${value}`);
  });
  return keys.length ? keys.join('\u0001') : undefined;
}

function tableDataRows(table: HTMLElement): HTMLElement[] {
  return Array.from(table.querySelectorAll<HTMLElement>('tbody tr, [role="row"]'))
    .filter((row) => !row.querySelector('[role="columnheader"]') && !row.closest('thead'));
}

function requiresTableIdentity(element: HTMLElement): boolean {
  if (!tableCellFor(element)) return false;
  const tag = element.tagName.toLowerCase();
  const role = element.getAttribute('role')?.toLowerCase() || '';
  return isEditable(element) || tag === 'button' || tag === 'a'
    || ['button', 'link', 'menuitem', 'combobox'].includes(role);
}

function hasUniqueTableIdentity(element: HTMLElement): boolean {
  const cell = tableCellFor(element);
  const table = cell ? tableForCell(cell) : null;
  const bodyTable = cell ? bodyTableForCell(cell) : null;
  const identity = tableRowIdentityFor(element);
  if (!table || !bodyTable || !identity) return false;
  const matches = tableDataRows(bodyTable).filter((row) => {
    const rowCell = rowCells(row)[0];
    return rowCell ? tableRowIdentityFor(rowCell) === identity : false;
  });
  return matches.length === 1;
}

function tableHeaderForElement(element: HTMLElement): string {
  const cell = tableCellFor(element);
  const row = cell?.closest<HTMLElement>('tr, [role="row"]');
  if (!cell || !row) return '';
  const table = tableForCell(cell);
  if (!table) return '';
  const index = rowCells(row).indexOf(cell);
  if (index < 0) return '';

  const headerRows = Array.from(table.querySelectorAll<HTMLElement>(
    'thead tr, .el-table__header-wrapper tr, [role="row"]',
  )).filter((candidate) => candidate !== row
    && (candidate.querySelector('[role="columnheader"]') || candidate.closest('thead')));
  const headerRow = headerRows[headerRows.length - 1];
  const header = headerRow ? rowCells(headerRow)[index] : undefined;
  return header ? summaryCellText(header) : '';
}

function tableRowSummaryFor(element: HTMLElement): string {
  const cell = tableCellFor(element);
  const row = cell?.closest<HTMLElement>('tr, [role="row"]');
  if (!cell || !row) return '';
  const cells = rowCells(row);
  const currentIndex = cells.indexOf(cell);
  if (currentIndex < 0) return '';
  const table = tableForCell(cell);
  if (!table) return '';

  const summaryParts: string[] = [];
  cells.forEach((candidate, index) => {
    const headerRow = headerRowForTable(table, row);
    const header = headerRow ? rowCells(headerRow)[index] : undefined;
    const headerText = header ? summaryCellText(header) : '';
    const identityColumn = headerText
      ? /单号|订单|物料|名称|编号|sku|order|material|product/i.test(headerText)
        || Boolean(/产品|商品/i.test(headerText) && productCellIdentity(candidate))
      : index < 2;
    if (!identityColumn) return;
    const value = summaryCellText(candidate);
    if (!value || hasSensitiveText(`${headerText} ${value}`)) return;
    summaryParts.push(`${headerText || `列${index + 1}`}=${value}`);
  });
  return truncate(summaryParts.join('；'), 80);
}

function isEditable(element: HTMLElement): boolean {
  if (isDisabled(element) || element.hasAttribute('readonly')
    || element.getAttribute('aria-readonly') === 'true') return false;
  if (element instanceof HTMLTextAreaElement) return true;
  if (!(element instanceof HTMLInputElement)) return false;
  return !['hidden', 'password', 'checkbox', 'radio', 'file', 'button', 'submit', 'reset', 'image'].includes(inputType(element));
}

function isPotentialControl(element: HTMLElement): boolean {
  const tag = element.tagName.toLowerCase();
  const role = element.getAttribute('role')?.toLowerCase() || '';
  return tag === 'input' || tag === 'textarea' || tag === 'select' || tag === 'button'
    || tag === 'a' || role === 'button' || role === 'combobox' || role === 'option'
    || role === 'radio' || role === 'checkbox' || role === 'menuitem'
    || element.classList.contains('el-sub-menu__title') || element.classList.contains('el-menu-item');
}

function isControlSafe(element: HTMLElement): boolean {
  if (isExcluded(element) || !isVisible(element) || isDisabled(element) || !safeHref(element)) return false;
  const type = inputType(element);
  const label = associatedLabel(element);
  const directLabel = directSemanticLabel(element);
  const semanticText = [
    directLabel, attributeText(element, 'name'), attributeText(element, 'id'), type,
  ].join(' ');
  if (type === 'hidden' || type === 'file' || hasSensitiveText(semanticText)
    || hasSensitiveText(readRawValue(element) || '')) return false;
  if (DESTRUCTIVE_RE.test(directLabel) || SECONDARY_AI_RE.test(directLabel)) return false;
  if (requiresTableIdentity(element) && !hasUniqueTableIdentity(element)) return false;
  return Boolean(label);
}

function controlRole(element: HTMLElement): string {
  const explicit = element.getAttribute('role')?.trim();
  if (explicit) return explicit;
  const tag = element.tagName.toLowerCase();
  if (tag === 'a') return 'link';
  if (tag === 'button') return 'button';
  if (tag === 'select') return 'combobox';
  if (tag === 'textarea') return 'textbox';
  if (tag === 'input') {
    const type = inputType(element);
    if (type === 'checkbox') return 'checkbox';
    if (type === 'radio') return 'radio';
    return 'textbox';
  }
  return 'button';
}

function stableLabelFor(element: HTMLElement): string {
  const aria = attributeText(element, 'aria-label');
  if (aria) return truncate(aria, MAX_LABEL);
  const labelledBy = attributeText(element, 'aria-labelledby');
  if (labelledBy) return truncate(labelledBy, MAX_LABEL);
  const id = attributeText(element, 'id');
  if (id) return id;
  const name = attributeText(element, 'name');
  if (name) return name;
  const placeholder = attributeText(element, 'placeholder');
  if (placeholder) return truncate(placeholder, MAX_LABEL);
  const role = element.getAttribute('role') || '';
  // A select/combobox's visible text is its current value and may legitimately
  // change after a click; do not treat that as an identity change.
  if (role === 'combobox' || role === 'option') return role;
  return associatedLabel(element);
}

function signatureFor(element: HTMLElement): ControlSignature {
  const tag = element.tagName.toLowerCase();
  const role = element.getAttribute('role') || '';
  return {
    tag: element.tagName,
    role: controlRole(element),
    type: inputType(element),
    id: attributeText(element, 'id'),
    name: attributeText(element, 'name'),
    stableLabel: stableLabelFor(element),
    displaySignature: /^(button|a)$/.test(tag)
      || ['button', 'link', 'option', 'menuitem'].includes(role)
      || element.classList.contains('el-sub-menu__title') || element.classList.contains('el-menu-item')
      ? textOf(element) : '',
    rowIdentity: tableRowIdentityFor(element) || '',
    // Keep the complete value for stale-action detection. The serialized
    // snapshot uses readValue(), which deliberately caps disclosure at 240.
    value: readRawValue(element) || '',
    checked: 'checked' in element && Boolean((element as HTMLInputElement).checked),
    required: element.hasAttribute('required'),
  };
}

function sameIdentity(actual: ControlSignature, expected: ControlSignature): boolean {
  return actual.tag === expected.tag && actual.role === expected.role && actual.type === expected.type
    && actual.id === expected.id && actual.name === expected.name
    && actual.stableLabel === expected.stableLabel && actual.displaySignature === expected.displaySignature
    && actual.rowIdentity === expected.rowIdentity
    && actual.required === expected.required;
}

interface RootSelection {
  roots: HTMLElement[];
  modalOpen: boolean;
}

function zIndexOf(element: HTMLElement): number {
  const value = computedStyleFor(element)?.zIndex || '';
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : 0;
}

function topmost(elements: readonly HTMLElement[]): HTMLElement | undefined {
  return elements.reduce<HTMLElement | undefined>((current, element) => {
    if (!current) return element;
    return zIndexOf(element) >= zIndexOf(current) ? element : current;
  }, undefined);
}

function visibleElements(doc: Document, selector: string): HTMLElement[] {
  return Array.from(doc.querySelectorAll<HTMLElement>(selector)).filter(isVisible);
}

function rootElements(doc: Document): RootSelection {
  const modal = topmost(visibleElements(doc, '.el-message-box, .el-dialog, .el-drawer'));
  const poppers = visibleElements(doc, '.el-popper');
  if (modal) {
    // A modal owns the foreground. Background content and routes are not
    // actionable while it is open; visible poppers remain eligible because
    // Element Plus appends them outside the modal subtree.
    return { roots: [modal, ...poppers.filter((element) => element !== modal)], modalOpen: true };
  }

  const roots: HTMLElement[] = [];
  const appendUnique = (elements: readonly HTMLElement[]): void => {
    elements.forEach((element) => {
      if (!roots.includes(element)) roots.push(element);
    });
  };
  // Popper options are the current foreground before the underlying page and
  // sidebar. This ordering also prevents a long background list consuming the
  // bounded control budget before the option the user just opened.
  appendUnique(poppers);
  appendUnique(visibleElements(doc, '.app-content'));
  appendUnique(visibleElements(doc, '.app-sidebar, aside[aria-label*="导航"], aside[aria-label*="nav" i], nav, [role="navigation"]'));
  return { roots, modalOpen: false };
}

function safeVisibleText(roots: readonly HTMLElement[]): string {
  const chunks: string[] = [];
  const walk = (node: Node): void => {
    if (chunks.join(' ').length >= MAX_PAGE_TEXT) return;
    if (node.nodeType === Node.TEXT_NODE) {
      const value = String(node.textContent || '').replace(/\s+/g, ' ').trim();
      if (value && !hasSensitiveText(value)) chunks.push(value);
      return;
    }
    if (!(node instanceof HTMLElement) || isExcluded(node) || !isVisible(node)) return;
    const sensitiveAttributes = [
      attributeText(node, 'aria-label'), attributeText(node, 'name'),
      attributeText(node, 'id'), attributeText(node, 'type'),
      attributeText(node, 'placeholder'),
    ].join(' ');
    if (hasSensitiveText(sensitiveAttributes)
      || (isPotentialControl(node) && (hasSensitiveText(associatedLabel(node))
        || SECONDARY_AI_RE.test(associatedLabel(node))))) return;
    node.childNodes.forEach(walk);
  };
  roots.forEach(walk);
  return truncate(chunks.join(' '), MAX_PAGE_TEXT);
}

function validInternalRoute(path: string): boolean {
  const trimmed = path.trim();
  return trimmed.startsWith('/') && !trimmed.startsWith('//') && !UNSAFE_SCHEME_RE.test(trimmed);
}

export function observeAgentPage(options: ObserveAgentPageOptions): AgentSnapshot {
  const selection = rootElements(options.document);
  const roots = selection.roots;
  const targets = new Map<string, HTMLElement>();
  const signatures = new Map<string, ControlSignature>();
  const controls: AgentControlSnapshot[] = [];
  const seen = new Set<HTMLElement>();

  roots.forEach((root) => {
    const candidates: HTMLElement[] = [];
    if (isPotentialControl(root)) candidates.push(root);
    root.querySelectorAll<HTMLElement>(
      'input, textarea, select, button, a[href], [role="button"], [role="combobox"], [role="option"], [role="radio"], [role="checkbox"], [role="menuitem"], .el-sub-menu__title, .el-menu-item',
    ).forEach((element) => candidates.push(element));

    candidates.forEach((element) => {
      if (controls.length >= 140) return;
      if (seen.has(element) || !isPotentialControl(element) || !isControlSafe(element)) return;
      // Element Plus select wrappers are the actionable surface. Do not expose
      // their readonly inner input as a second, misleading control.
      const comboboxAncestor = element.parentElement?.closest('[role="combobox"]');
      if (comboboxAncestor && comboboxAncestor !== element) return;
      seen.add(element);

      const ref = `c${controls.length + 1}`;
      const label = truncate(associatedLabel(element), MAX_LABEL);
      const value = readValue(element);
      const control: AgentControlSnapshot = {
        ref,
        role: controlRole(element),
        label,
      };
      if (value !== undefined && value.length > 0) control.value = value;
      if (element.hasAttribute('required')) control.required = true;
      const type = inputType(element);
      if (type) control.inputType = type;
      targets.set(ref, element);
      signatures.set(ref, signatureFor(element));
      controls.push(control);
    });
  });

  const routeTargets = new Map<string, AgentRoute>();
  const routeSnapshots: AgentRouteSnapshot[] = [];
  const seenPaths = new Set<string>();
  if (!selection.modalOpen) options.routes.forEach((route) => {
    if (!route || !validInternalRoute(route.path) || seenPaths.has(route.path)) return;
    const ref = `r${routeSnapshots.length + 1}`;
    const safeRoute: AgentRoute = { path: route.path, title: truncate(route.title, MAX_LABEL) };
    seenPaths.add(route.path);
    routeTargets.set(ref, safeRoute);
    routeSnapshots.push({ ref, path: safeRoute.path, title: safeRoute.title });
  });

  const snapshot: AgentSnapshot = {
    id: `agent-page-${Date.now()}-${++snapshotSequence}`,
    route: options.route,
    page: {
      title: hasSensitiveText(options.title) ? '当前页面' : truncate(options.title, MAX_LABEL),
      text: safeVisibleText(roots),
      controls,
      routes: routeSnapshots,
    },
    targets,
    routeTargets,
  };
  snapshotState.set(snapshot, { signatures });
  return snapshot;
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function invalidAction(message: string): never {
  throw new Error(`Invalid agent action: ${message}`);
}

export function validateAgentAction(raw: unknown, snapshot: AgentSnapshot): AgentAction {
  if (!isPlainRecord(raw)) return invalidAction('action must be an object');
  const allowed = new Set(['kind', 'target', 'value', 'message']);
  if (Object.keys(raw).some((key) => !allowed.has(key))) return invalidAction('unknown action field');
  if (typeof raw.kind !== 'string' || !['click', 'fill', 'navigate', 'wait', 'ask', 'done'].includes(raw.kind)) {
    return invalidAction('unknown action kind');
  }
  if (typeof raw.message !== 'string' || !raw.message.trim() || raw.message.length > MAX_MESSAGE) {
    return invalidAction('message is required');
  }
  if (raw.target !== undefined && typeof raw.target !== 'string') return invalidAction('target must be a string');
  if (raw.value !== undefined && typeof raw.value !== 'string') return invalidAction('value must be a string');

  const kind = raw.kind as AgentActionKind;
  const target = raw.target as string | undefined;
  const value = raw.value as string | undefined;
  if (['wait', 'ask', 'done'].includes(kind) && (target !== undefined || value !== undefined)) {
    return invalidAction(`${kind} cannot carry target/value`);
  }
  if (kind === 'click' && (target === undefined || value !== undefined)) return invalidAction('click requires only target');
  if (kind === 'fill' && (target === undefined || value === undefined)) return invalidAction('fill requires target/value');
  if (kind === 'navigate' && (target === undefined || value !== undefined)) return invalidAction('navigate requires only route target');

  if (kind === 'click' && !snapshot.targets.has(target!)) return invalidAction('unknown or stale control ref');
  if (kind === 'fill') {
    const element = snapshot.targets.get(target!);
    if (!element || !isEditable(element)) return invalidAction('target is not an editable non-sensitive input');
    if (value!.length > MAX_VALUE * 4) return invalidAction('fill value is too long');
  }
  if (kind === 'navigate' && !snapshot.routeTargets.has(target!)) return invalidAction('unknown route ref');

  return { kind, ...(target === undefined ? {} : { target }), ...(value === undefined ? {} : { value }), message: raw.message } as AgentAction;
}

function requireLiveTarget(snapshot: AgentSnapshot, ref: string, checkCoverage = false): HTMLElement {
  const element = snapshot.targets.get(ref);
  const expected = snapshotState.get(snapshot)?.signatures.get(ref);
  if (!element || !expected || !element.isConnected || !isVisible(element) || isDisabled(element)
    || !isControlSafe(element) || !sameIdentity(signatureFor(element), expected)) {
    throw new Error('Agent action rejected: stale, hidden, disabled, or changed control');
  }
  if (checkCoverage && !isTopmostAtCenter(element)) {
    throw new Error('Agent action rejected: target is offscreen or covered');
  }
  return element;
}

function requireUnchangedValue(element: HTMLElement, expected: ControlSignature): void {
  const current = signatureFor(element);
  if (current.value !== expected.value || current.checked !== expected.checked) {
    throw new Error('Agent action rejected: control value changed since observation');
  }
}

function dispatchInputEvents(element: HTMLInputElement | HTMLTextAreaElement): void {
  const EventCtor = element.ownerDocument.defaultView?.Event || Event;
  element.dispatchEvent(new EventCtor('input', { bubbles: true }));
  element.dispatchEvent(new EventCtor('change', { bubbles: true }));
}

function setNativeValue(element: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value')?.set;
  if (!setter) throw new Error('Agent action rejected: input has no native value setter');
  setter.call(element, value);
  dispatchInputEvents(element);
}

export async function executeAgentAction(
  snapshot: AgentSnapshot,
  action: AgentAction,
  options: ExecuteAgentActionOptions,
): Promise<{ label: string; before?: string; after?: string; path?: string }> {
  const validated = validateAgentAction(action, snapshot);
  if (validated.kind === 'wait' || validated.kind === 'ask' || validated.kind === 'done') {
    return { label: validated.message };
  }

  if (validated.kind === 'navigate') {
    options.assertCurrent();
    const route = snapshot.routeTargets.get(validated.target!);
    if (!route) throw new Error('Agent action rejected: stale route ref');
    return { label: validated.message, path: route.path };
  }

  const expected = snapshotState.get(snapshot)?.signatures.get(validated.target!);
  if (!expected) throw new Error('Agent action rejected: snapshot is not executable');
  const element = requireLiveTarget(snapshot, validated.target!);
  requireUnchangedValue(element, expected);
  options.assertCurrent();
  if (options.beforeAction) await options.beforeAction(element);
  options.assertCurrent();

  const current = requireLiveTarget(snapshot, validated.target!, true);
  requireUnchangedValue(current, expected);
  const label = associatedLabel(current);
  const before = readValue(current) ?? label;

  if (validated.kind === 'fill') {
    if (!(current instanceof HTMLInputElement || current instanceof HTMLTextAreaElement) || !isEditable(current)) {
      throw new Error('Agent action rejected: target is no longer editable');
    }
    setNativeValue(current, validated.value!);
  } else {
    current.click();
    // A successful click may intentionally close/remove/navigate away from
    // the control (select option, modal action, router link). The runtime
    // assertion is the post-action boundary; do not require the old DOM node
    // to remain visible or connected afterward.
    options.assertCurrent();
    const after = readValue(current) ?? '已点击';
    return { label, before, after };
  }

  options.assertCurrent();
  const afterElement = requireLiveTarget(snapshot, validated.target!, true);
  const after = readValue(afterElement) ?? associatedLabel(afterElement);
  if (validated.kind === 'fill' && readRawValue(afterElement) !== validated.value) {
    throw new Error('Agent action rejected: native input value did not settle');
  }
  return { label, before, after };
}
