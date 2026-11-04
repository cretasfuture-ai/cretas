/**
 * 产出报工的工艺参数录入栏 (docs/specs/app-process-params-entry.md):
 *  - 没有 enabled 字段 → 整段不出现 (阴性对照);
 *  - 数字字段常驻标准文案; 超范围变红且带「偏高 · / 偏低 · 」; 非数字「请填数字」;
 *  - 非数字类型字段是普通文本输入框。
 */
import fs from 'fs';
import path from 'path';
import React, { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';
import ProcessParamsSection from '../../../components/processing/ProcessParamsSection';
import type { ProcessCustomFieldDef } from '../../../services/api/yieldReportApi';

const SCHEMA: ProcessCustomFieldDef[] = [
  { key: 'oilTemp', label: '油温', type: 'number', enabled: true, unit: '℃', standardValue: 155, min: 150, max: 160 },
  { key: 'moisture', label: '水分', type: 'number', enabled: true, unit: '%', max: 12 },
  { key: 'hidden', label: '停用项', type: 'number', enabled: false, unit: 'g', max: 1 },
  { key: 'remark', label: '备注', type: 'text', enabled: true },
];

const Harness: React.FC<{ schema: ProcessCustomFieldDef[] | null | undefined }> = ({ schema }) => {
  const [values, setValues] = useState<Record<string, string>>({});
  return (
    <ProcessParamsSection
      processName="炒制"
      schema={schema}
      values={values}
      onChangeValue={(k, t) => setValues((p) => ({ ...p, [k]: t }))}
    />
  );
};

const hintOf = (key: string): string => {
  const children = screen.getByTestId(`process-param-hint-${key}`).props.children;
  return Array.isArray(children) ? children.join('') : String(children);
};
const colorOf = (key: string): string | undefined => {
  const style = screen.getByTestId(`process-param-hint-${key}`).props.style;
  const flat = Object.assign({}, ...(Array.isArray(style) ? style.flat(2).filter(Boolean) : [style]));
  return flat.color;
};

describe('ProcessParamsSection', () => {
  it.each([[null], [undefined], [[]], [[{ key: 'x', label: 'x', type: 'number', enabled: false } as ProcessCustomFieldDef]]])(
    '阴性对照: 没有 enabled 字段 (%p) → 不渲染',
    (schema) => {
      render(<Harness schema={schema as ProcessCustomFieldDef[] | null | undefined} />);
      expect(screen.queryByTestId('process-params-section')).toBeNull();
    },
  );

  it('提示排在输入框上方: 键盘是 pan 模式, 框下面的内容填写时会被盖住 (小米真机实测)', () => {
    const src = fs.readFileSync(
      path.resolve(__dirname, '../../../components/processing/ProcessParamsSection.tsx'),
      'utf8',
    );
    const hintAt = src.indexOf('testID={`process-param-hint-');
    const inputAt = src.indexOf('<PaperTextInput\n') === -1
      ? src.indexOf('<PaperTextInput\r\n')
      : src.indexOf('<PaperTextInput\n');
    expect(hintAt).toBeGreaterThan(-1);
    expect(inputAt).toBeGreaterThan(-1);
    expect(hintAt).toBeLessThan(inputAt);
  });

  it('标题带工序名 + 副标题; 停用项不出现; 数字项常驻标准文案(灰)', () => {
    render(<Harness schema={SCHEMA} />);
    expect(screen.getByText('炒制 工艺参数')).toBeTruthy();
    expect(screen.getByText('按这锅实际填写，没测的可以不填')).toBeTruthy();
    expect(screen.queryByTestId('process-param-input-hidden')).toBeNull();
    expect(hintOf('oilTemp')).toBe('标准 155℃，范围 150–160℃');
    expect(hintOf('moisture')).toBe('不高于 12%');
    expect(colorOf('oilTemp')).toBe('#909399');
    expect(screen.queryByTestId('process-param-hint-remark')).toBeNull();
  });

  it('数字输入框用 decimal-pad 且字号 >= 24; 文本项用默认键盘', () => {
    render(<Harness schema={SCHEMA} />);
    expect(screen.getByTestId('process-param-input-oilTemp').props.keyboardType).toBe('decimal-pad');
    expect(screen.getByTestId('process-param-input-remark').props.keyboardType).toBe('default');
    const style = screen.getByTestId('process-param-input-oilTemp').props.style;
    const flat = Object.assign({}, ...(Array.isArray(style) ? style.flat(2).filter(Boolean) : [style]));
    expect(flat.fontSize).toBeGreaterThanOrEqual(24);
  });

  it('超范围变红带方向; 回到范围内恢复灰; 边界算合格', () => {
    render(<Harness schema={SCHEMA} />);
    fireEvent.changeText(screen.getByTestId('process-param-input-oilTemp'), '165');
    expect(hintOf('oilTemp')).toBe('偏高 · 标准 155℃，范围 150–160℃');
    expect(colorOf('oilTemp')).toBe('#F56C6C');
    fireEvent.changeText(screen.getByTestId('process-param-input-oilTemp'), '149');
    expect(hintOf('oilTemp')).toBe('偏低 · 标准 155℃，范围 150–160℃');
    fireEvent.changeText(screen.getByTestId('process-param-input-oilTemp'), '160');
    expect(hintOf('oilTemp')).toBe('标准 155℃，范围 150–160℃');
    expect(colorOf('oilTemp')).toBe('#909399');
  });

  it('数字项里填非数字 → 红字「请填数字」', () => {
    render(<Harness schema={SCHEMA} />);
    fireEvent.changeText(screen.getByTestId('process-param-input-moisture'), '很高');
    expect(hintOf('moisture')).toBe('请填数字');
    expect(colorOf('moisture')).toBe('#F56C6C');
  });
});
