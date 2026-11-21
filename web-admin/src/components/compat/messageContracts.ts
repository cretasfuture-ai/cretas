import type { CSSProperties, VNode } from 'vue';

export type BoxMode = 'confirm' | 'alert' | 'prompt';
export type BoxResult = { action: 'confirm' | 'cancel' | 'close'; value: string };
export type Text = string | VNode | (() => VNode);
export type BoxOptions = {
  type?: 'primary' | 'success' | 'warning' | 'error' | 'info' | '';
  showCancelButton?: boolean; showConfirmButton?: boolean; showClose?: boolean;
  closeOnClickModal?: boolean; closeOnPressEscape?: boolean; distinguishCancelAndClose?: boolean;
  confirmButtonText?: string; confirmButtonClass?: string; cancelButtonText?: string; cancelButtonClass?: string;
  inputValue?: string; inputType?: string; inputPlaceholder?: string; inputPattern?: RegExp;
  inputValidator?: (value: string) => boolean | string | undefined; inputErrorMessage?: string;
  dangerouslyUseHTMLString?: boolean;
  customStyle?: CSSProperties;
  callback?: (action: BoxResult['action']) => unknown;
};
