/** 对话建厂页里把服务端的原始报错换成人话；没有对应说法的原样返回。 */
const FOREIGN_SESSION =
  '这次建厂访谈是另一位操作人发起的，你的账号不能接着回答它。已保存的蓝图你仍然可以查看和复核；'
  + '要重新答题，请点「开始新的访谈版本」，系统会为你的账号单独开一份访谈。'

export function interviewErrorText(raw: string): string {
  return raw.includes('会话不存在或不属于当前用户') ? FOREIGN_SESSION : raw
}
