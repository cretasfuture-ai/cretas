const words = (list = '') => new Set(list.split(' ').filter(Boolean));
const direct = (component, props, rename = {}, values = {}) => ({
  component, props: words(props), rename, values,
});

const colors = { primary: 'primary', success: 'success', warning: 'warning', danger: 'error', info: 'info', error: 'error' };
const sizes = { large: 'lg', default: 'md', small: 'sm' };

export const mappings = {
  'el-button': direct('UButton', 'type size disabled loading autofocus tabindex link text plain native-type',
    { type: 'color', link: 'variant', text: 'variant', plain: 'variant', 'native-type': 'type' },
    { type: { ...colors, '': 'neutral' }, size: sizes, link: { '': 'link' }, text: { '': 'ghost' }, plain: { '': 'outline' } }),
  'el-tag': direct('UBadge', 'type size effect round', { type: 'color', effect: 'variant' }, { type: colors, size: sizes, effect: { plain: 'outline', dark: 'solid', light: 'soft' } }),
  'el-input': direct('UInput', 'type size placeholder disabled readonly autocomplete autofocus maxlength minlength clearable name id inputmode', { clearable: null }, { size: sizes, type: { text: 'text', password: 'password', number: 'number', email: 'email', search: 'search', tel: 'tel', url: 'url' } }),
  'el-select': direct('CompatSelect', 'multiple disabled clearable filterable placeholder size name id'),
  'el-option': direct('CompatOption', 'value label disabled'),
  'el-card': direct('UCard', 'shadow header', { shadow: null, header: null }),
  'el-alert': direct('CompatAlert', 'type title description effect closable', { type: 'color', effect: 'variant', closable: 'close' }, { type: colors, effect: { light: 'subtle', dark: 'solid' } }),
  'el-switch': direct('USwitch', 'disabled loading name id size', {}, { size: sizes }),
  'el-tooltip': direct('UTooltip', 'content disabled', { content: 'text' }),
  'el-empty': direct('UEmpty', 'description'),
  'el-icon': direct('span', '', {}),
  'el-text': direct('span', 'tag', { tag: null }),
  'el-radio': direct('CompatRadio', 'label value disabled name'),
  'el-checkbox': direct('UCheckbox', 'disabled label name id indeterminate'),
  'el-divider': direct('USeparator', 'direction', { direction: 'orientation' }),
  'el-link': direct('ULink', 'href target rel disabled', { href: 'to' }),
  'el-badge': direct('CompatBadge', 'value hidden max is-dot show-zero type'),
  'el-progress': direct('UProgress', 'percentage status show-text', { percentage: 'model-value', status: 'color', 'show-text': 'status' }, { status: { success: 'success', warning: 'warning', exception: 'error' } }),
  'el-skeleton': direct('CompatSkeleton', 'loading rows animated count'),
  'el-table': direct('CompatTable', 'data row-key border stripe size height max-height empty-text highlight-current-row'),
  'el-table-column': direct('CompatTableColumn', 'prop label width min-width fixed formatter type align show-overflow-tooltip selectable index'),
  'el-form': direct('CompatForm', 'model rules label-width label-position inline disabled size validate-on-rule-change'),
  'el-form-item': direct('CompatFormItem', 'prop label required rules error label-width'),
  'el-dialog': direct('CompatDialog', 'title width before-close close-on-click-modal close-on-press-escape show-close destroy-on-close append-to-body align-center center'),
  'el-date-picker': direct('CompatDatePicker', 'type value-format format disabled clearable placeholder start-placeholder end-placeholder range-separator name id size'),
  'el-descriptions': direct('CompatDescriptions', 'title column border size direction'),
  'el-descriptions-item': direct('CompatDescriptionsItem', 'label span width min-width align label-align'),
  'el-pagination': direct('CompatPagination', 'current-page page-size total page-count page-sizes layout pager-count background small disabled hide-on-single-page'),
};

export const sharedAttributes = words('class style key ref title role tabindex id');
export const safeEvents = {
  'el-button': words('click focus blur'),
  'el-input': words('focus blur keydown keyup'),
  'el-alert': words('close'),
  'el-select': words('change clear'),
  'el-radio': words('change'),
  'el-table': words('selection-change row-click current-change'),
  'el-form': words('submit'),
  'el-dialog': words('open opened close closed'),
  'el-date-picker': words('change clear'),
  'el-pagination': words('current-change size-change change prev-click next-click update:current-page update:page-size'),
};
