import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 已放弃的题：**从「当前这一题」里拿掉**，但**必须带着原因显示出来**。
 *
 * <h2>治的那个缺陷（2026-08-18 真实浏览器实测）</h2>
 *
 * 一项问了两次没拿到被放弃之后，它仍然没有值 ⇒ 仍在 `pending` 里 ⇒
 * `pendingItems[0]` 恒指着它，而会话早问到别的题去了：
 *
 *     界面标签      第2道·投入主原料     ← 已经放弃的那一题
 *     对话里在问    第4道·工序名称       ← 系统真正等的答案
 *
 * 客户照标签答 → 答案被当成另一题的回答 → 抽不出来 → 又放弃 → 级联。
 *
 * ⚠️ 接口探针一直没发现，因为探针读的是会话给的 `nextQuestion`（那份是对的），
 *    界面读的是 `progress().pendingItems`。**两份判据，只有真走页面才撞得出来。**
 *
 * <h2>⛔ 判据不是「把放弃的项藏起来」</h2>
 *
 * 藏起来就是目标里明令不许的「被系统悄悄放弃」。它们移进 `skippedItems`，
 * 带原因显示 —— 这一条同样有断言守着（第三条）。
 *
 * <h2>⚠️ 为什么这里读源码</h2>
 *
 * 后端那一半（谁进 pendingItems、谁进 skippedItems）已由
 * `CurrentQuestionIsTheAskedOneTest` 走**真实入口** progress() 钉住，并有变异对照。
 * 这里钉的是**前端有没有接上**：拿到了 skippedItems 却不画，等于后端白改。
 */
describe('已放弃的题：不当「当前这一题」，也不许悄悄消失', () => {
  const HERE = dirname(fileURLToPath(import.meta.url))
  const PAGE = readFileSync(resolve(HERE, '..', 'index.vue'), 'utf8')

  /** ⛔ 先剥注释再数 —— 本仓栽过三次「闸把它自己要求写下的注释数了进去」。 */
  const CODE = PAGE
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')

  it('「当前这一题」取 pendingItems[0] —— 而那一份服务端已排除放弃项', () => {
    expect(CODE).toContain('progress.value?.pendingItems?.[0]')
  })

  it('🔴 放弃的项要画出来，带原因', () => {
    expect(CODE, '⛔ 没读 skippedItems = 后端给了、前端没接（形态 B）')
      .toContain('progress.skippedItems')
    expect(CODE, '⛔ 不说原因 = 只告诉他缺了，没告诉他为什么').toContain('it.why')
  })

  it('⛔ 前端不许自己判断哪些被放弃了', () => {
    // 「问了几次、放弃没放弃」只有会话知道。前端再推一遍就是同一个东西两份，一定会漂。
    expect(CODE).not.toMatch(/askAttempts/)
    expect(CODE).not.toMatch(/skipReason\s*[!=]==?\s*null/)
  })

  it('⚠️ 放弃项与「还没问到」要分得开 —— 那一类还会问，这一类不会了', () => {
    expect(CODE, '两类共用同一句文案，客户分不清哪些还会被问到')
      .toContain('ck-skipped')
    expect(CODE).toContain('ck-empty')
  })
})
