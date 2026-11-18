/**
 * Markdown 转纯文本摘要（列表预览用，不引入渲染依赖）。
 * 去掉标题 #、列表符 - * +、加粗/斜体/行内代码/代码围栏、链接与图片语法、引用 >、表格竖线与分隔行，
 * 压缩空白后取前 maxLen 个字，超出补「…」。
 */
export function markdownToPlainSummary(markdown: string | null | undefined, maxLen = 80): string {
  if (!markdown) return '';
  const text = markdown
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}#{1,6}\s*/gm, '')
    .replace(/^\s*>+\s?/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*\d+[.)]\s+/gm, '')
    .replace(/^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/gm, ' ')
    .replace(/\|/g, ' ')
    .replace(/(\*\*|__|~~)/g, '')
    .replace(/(^|[^*\w])[*_]([^*_\n]+)[*_]/g, '$1$2')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
  return text.length > maxLen ? `${text.slice(0, maxLen)}…` : text;
}
