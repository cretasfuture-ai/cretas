/**
 * AI 对话面的字体与遥测开关 (标准: docs 里的 ai-chat-standard T1 / E2)。
 *
 * - T1: 对话区只用一套系统字体栈。Paper 的 MD3 主题在 web 上把字体写死成
 *   `Roboto, "Helvetica Neue", Helvetica, Arial`, 而 RN 原生 Text 在 web 上又是另一套, 同一屏混用三套。
 * - E2: 遥测徽标 (来源 / 耗时 / 模式) 只在显式开关下给开发者看。
 *   ⛔ 不用 `__DEV__`: RN 网页版是 expo start 开发服务器, `__DEV__` 恒为 true, 业务用户验收时就漏出来了。
 */
import { Platform } from 'react-native';
import { configureFonts, type MD3Theme } from 'react-native-paper';

export const CHAT_FONT_STACK =
  '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif';

/** 只在 web 上写死字体栈; iOS / Android 用 Paper 默认 (System / sans-serif), 本来就是系统字体 */
export const chatFontFamily: { fontFamily?: string } =
  Platform.OS === 'web' ? { fontFamily: CHAT_FONT_STACK } : {};

/** 对话面的嵌套 Paper 主题: web 上把 fonts 换成系统字体栈, 其它平台原样返回。⛔ 不改全局 src/theme。 */
export function withChatFonts<T extends MD3Theme>(theme: T): T {
  if (Platform.OS !== 'web') return theme;
  return { ...theme, fonts: configureFonts({ config: { fontFamily: CHAT_FONT_STACK } }) };
}

/** 遥测显式开关。⛔ 不用 __DEV__ (标准 E2) */
export const AI_DEBUG_TELEMETRY = process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY === '1';
