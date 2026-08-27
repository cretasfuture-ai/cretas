/**
 * `/ai-intents/execute` 响应状态的判读 —— 唯一实现。
 *
 * ## 🔴 它为什么存在（2026-08-28 生产实测）
 *
 * 财务主管工作台的主区块「月度经营摘要」**永远显示一句拒答**：
 *
 * ```
 * 「月度财务复盘」是写入/修改操作，执行前需要确认。
 * ```
 *
 * 成因是这一行：
 *
 * ```ts
 * formattedText.value = response.formattedText || response.message || '(无输出)';
 * ```
 *
 * **它完全不看 `status`** —— 只要 HTTP 200 就当成功，于是后端的
 * `WRITE_CONFIRM_REQUIRED` / `NEED_MORE_INFO` / `PERMISSION_DENIED` 这类
 * **非答案**回执，被原样当成「摘要正文」渲染出来，还挂在成功的标题下面。
 *
 * ⚠️ 这不是一屏的问题：7 个工作台是同一行写法。
 * （`AIQuery.vue` 是**对的** —— 它按 `responseStatus` 分支，本文件的状态清单就是从它抄的，
 *  ⛔ 不是我另发明一套。）
 *
 * ## ⛔ 这里只判「是不是答案」，不判「答得对不对」
 *
 * 后者是各屏自己的事。本文件唯一的职责是：**别把拒答当答案。**
 */

/** 后端认为「这次给出了答案」的状态。其余一律不是答案。 */
const ANSWER_STATUSES = new Set(['SUCCESS', 'COMPLETED']);

/**
 * 非答案状态 → 给用户看的短标签。
 *
 * ⚠️ 措辞是给**工厂用户**看的，⛔ 不要把 `WRITE_CONFIRM_REQUIRED` 这种码直接显示出去。
 */
const NON_ANSWER_LABELS: Record<string, string> = {
  WRITE_CONFIRM_REQUIRED: '需要确认后才能执行',
  READ_MODE_WRITE_BLOCKED: '当前为只读模式，未执行',
  NEED_MORE_INFO: '需要补充信息',
  NEED_CLARIFICATION: '需要补充信息',
  CONVERSATION_CONTINUE: '需要补充信息',
  TOOL_DISABLED: '该能力当前未启用',
  PERMISSION_DENIED: '没有权限',
  FAILED: '没能完成',
  ERROR: '没能完成',
};

/**
 * 这次回执是不是一个**答案**。
 *
 * ⚠️ 空 status 按答案处理 —— 老接口不带 status，⛔ 不能因为本次改动把它们全判成失败。
 * 真正要拦的是「后端明说了不是答案」的那些。
 */
export function isIntentAnswer(status?: string | null): boolean {
  const upper = String(status || '').toUpperCase();
  if (!upper) return true;
  if (ANSWER_STATUSES.has(upper)) return true;
  return !(upper in NON_ANSWER_LABELS);
}

/** 非答案状态的用户可读标签；是答案时返回 `null`。 */
export function intentNonAnswerLabel(status?: string | null): string | null {
  const upper = String(status || '').toUpperCase();
  if (isIntentAnswer(upper)) return null;
  return NON_ANSWER_LABELS[upper] ?? '暂时没能给出结果';
}

export const INTENT_NON_ANSWER_STATUSES = Object.freeze(Object.keys(NON_ANSWER_LABELS));
