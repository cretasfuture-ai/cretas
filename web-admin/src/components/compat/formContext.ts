import type { InjectionKey } from 'vue';

export type FieldPath = string | string[];
export type FormItem = {
  name: () => string; rules: () => unknown; required: () => boolean; element: () => HTMLElement | undefined;
};
export type FormErrors = Record<string, { message?: string; field?: string }[]>;
export type FormCallback = (valid: boolean, fields?: FormErrors) => void | Promise<void>;
export const formKey: InjectionKey<{
  items: Set<FormItem>;
  errors: Record<string, string>;
  labelWidth: () => string | number | undefined;
  orientation: () => 'horizontal' | 'vertical';
  validateField: (path: FieldPath | undefined, callback?: FormCallback, trigger?: string) => Promise<boolean>;
}> = Symbol('Element form compatibility');
