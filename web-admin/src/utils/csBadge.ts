/** Element 的 tag type → Nuxt UI UBadge color（颜色最终落在 --cs-* 上，见 styles/nuxt-ui.css）。 */
export type ElementTagType = 'primary' | 'success' | 'warning' | 'danger' | 'info' | undefined | '';
export type CsBadgeColor = 'primary' | 'success' | 'warning' | 'error' | 'info' | 'neutral';
const MAP: Record<string, CsBadgeColor> = { primary: 'primary', success: 'success', warning: 'warning', danger: 'error', info: 'neutral' };
export function badgeColor(t: ElementTagType): CsBadgeColor {
  return (t && MAP[t]) || 'neutral';
}
