/**
 * BP-38: 「检查…」类问题只要结论, 不能生成改画布的方案。
 *
 * 这是检查意图的**唯一**判定: 面板据此给请求带 `intent: 'check'`, 后端只认这个显式标记
 * 走只读提示词(不在后端再维护一份关键词表, 两份一定会漂)。
 *
 * 判定口径: 以检查/核对类动词开头, 且整句没有任何「改」的动词。
 * 「检查并补齐单位」含「补齐」⇒ 是修改请求, 照常走方案通道。
 */
const CHECK_LEAD = /^(请|帮我|麻烦|帮忙)?\s*(检查|核对|核查|诊断|审查|评估|看看|看一下|分析一下)/;
const CHANGE_VERBS = /(生成|调整|修改|改成|改为|改一下|改掉|添加|增加|新增|加上|删除|删掉|去掉|移除|替换|补上|补齐|补全|填上|设置|设为|配置成|配成|创建|新建|合并|拆分|修复|修正|更正|应用|重建|换成)/;

export function isWorkflowCheckOnlyRequest(text: string): boolean {
  const trimmed = String(text || '').trim();
  return CHECK_LEAD.test(trimmed) && !CHANGE_VERBS.test(trimmed);
}
