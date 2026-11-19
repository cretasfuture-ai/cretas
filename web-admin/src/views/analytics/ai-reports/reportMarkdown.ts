import { marked } from 'marked';
import DOMPurify from 'dompurify';

const ISO_RE = /(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?/g;

/** ISO 时间串（含纳秒/时区）→ YYYY-MM-DD HH:mm；按串内墙钟时间显示，不做时区换算。 */
export function formatReportTime(value?: string | null): string {
  if (!value) return '—';
  return value.replace(ISO_RE, '$1 $2');
}

/** 报告正文 markdown → 经 DOMPurify 净化的 HTML；正文内的 ISO 时间同样格式化。 */
export function renderReportMarkdown(source: string): string {
  if (!source) return '';
  const text = source.replace(ISO_RE, '$1 $2');
  return DOMPurify.sanitize(marked.parse(text, { async: false }) as string);
}
