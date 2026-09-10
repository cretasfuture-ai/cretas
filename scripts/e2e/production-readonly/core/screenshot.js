'use strict';

function slug(value) {
  return String(value || 'evidence').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80) || 'evidence';
}

// Mask contact text at capture time, without modifying application values or
// hiding adjacent business quantities. Rectangles are removed even on failure.
async function installContactMasks(page) {
  return page.evaluate(() => {
    const marker = 'data-cretas-contact-mask';
    document.querySelectorAll(`[${marker}]`).forEach((node) => node.remove());
    let count = 0;
    const cover = (rect) => {
      if (rect.width <= 0 || rect.height <= 0) return;
      const mask = document.createElement('div');
      mask.setAttribute(marker, '');
      Object.assign(mask.style, { position: 'absolute', left: `${rect.left + scrollX}px`, top: `${rect.top + scrollY}px`, width: `${rect.width}px`, height: `${rect.height}px`, background: '#1f2937', zIndex: '2147483647', pointerEvents: 'none' });
      document.body.append(mask); count += 1;
    };
    const contactHeader = /电话|手机|邮箱|电子邮件|e-?mail|phone|mobile|\btel\b/i;
    for (const table of document.querySelectorAll('.el-table, table')) {
      const headers = [...table.querySelectorAll('thead th')];
      const indexes = headers.flatMap((header, index) => contactHeader.test(header.textContent || '') ? [index] : []);
      for (const row of table.querySelectorAll('tbody tr')) {
        for (const index of indexes) if (row.children[index]) cover(row.children[index].getBoundingClientRect());
      }
    }
    for (const input of document.querySelectorAll('input')) {
      const label = `${input.getAttribute('type') || ''} ${input.getAttribute('name') || ''} ${input.getAttribute('autocomplete') || ''} ${input.getAttribute('placeholder') || ''} ${input.closest('.el-form-item')?.querySelector('label')?.textContent || ''}`;
      if (contactHeader.test(label)) cover(input.getBoundingClientRect());
    }
    const patterns = [/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, /(?<![A-Za-z0-9])(?:\+?86[ -]?)?1[3-9]\d{9}(?!\d)/g, /(?<![A-Za-z0-9])(?:\+\d{1,3}[ -])?(?:\(?\d{2,4}\)?[- ])\d{3,4}[- ]\d{4}(?!\d)/g];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const texts = [];
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (!node.parentElement?.closest('script, style, noscript, [data-cretas-contact-mask]')) texts.push(node);
    }
    for (const node of texts) for (const pattern of patterns) {
      pattern.lastIndex = 0;
      for (const match of node.textContent.matchAll(pattern)) {
        const range = document.createRange();
        range.setStart(node, match.index); range.setEnd(node, match.index + match[0].length);
        for (const rect of range.getClientRects()) cover(rect);
      }
    }
    return count;
  });
}

function createScreenshotWriter(page, evidenceDir, config = {}) {
  const root = String(evidenceDir || '').replace(/\\/g, '/').replace(/\/+$/, '');
  if (!root) throw new Error('Evidence directory is required for screenshots');
  let counter = 0;
  return async function screenshot(name, options = {}) {
    const filename = `${String(++counter).padStart(2, '0')}-${slug(name)}.png`;
    const target = `${root}/${filename}`;
    const mask = [
      page.locator('input[type="password"], input[placeholder*="用户名"]'),
      ...(config.sensitiveTexts || []).map((text) => page.getByText(text, { exact: true })),
    ];
    await installContactMasks(page);
    try { await page.screenshot({
      path: target,
      fullPage: Boolean(options.fullPage),
      mask,
      maskColor: '#1f2937',
    }); } finally {
      await page.evaluate(() => document.querySelectorAll('[data-cretas-contact-mask]').forEach((node) => node.remove())).catch(() => {});
    }
    return target;
  };
}

module.exports = { createScreenshotWriter, slug, installContactMasks };
