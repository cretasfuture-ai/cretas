import { beforeEach, describe, expect, it } from 'vitest';
import type { PeriodSelection } from '@/components/smartbi/PeriodSelector.vue';
import {
  factoryChartRequestFromSelection,
  factoryChartStorageKey,
  loadFactoryChartSelection,
  saveFactoryChartSelection,
} from '../factoryChartState';

const allHistory: PeriodSelection = {
  type: 'year', year: 2026, value: '2026', compareEnabled: false, allHistory: true,
};

beforeEach(() => localStorage.clear());

describe('factoryChartState', () => {
  it('maps all history to the factory recommendation contract', () => {
    expect(factoryChartRequestFromSelection({
      factoryId: 'F006', source: 'business', uploadId: null, period: allHistory,
      includeOptional: false, useAi: false,
    })).toEqual({
      source: 'business', factory_id: 'F006', year: 2026, period_type: 'all',
      start_month: 1, end_month: 12, include_optional: false, use_ai: false,
    });
  });

  it('maps a month range and refuses upload requests without an upload id', () => {
    const monthRange: PeriodSelection = {
      type: 'month_range', year: 2026, value: ['2026-02', '2026-05'], compareEnabled: false,
    };
    expect(factoryChartRequestFromSelection({
      factoryId: 'F006', source: 'upload', uploadId: 42, period: monthRange,
      includeOptional: true, useAi: true,
    })).toMatchObject({ source: 'upload', upload_id: 42, period_type: 'month_range', start_month: 2, end_month: 5 });
    expect(factoryChartRequestFromSelection({
      factoryId: 'F006', source: 'upload', uploadId: null, period: allHistory,
      includeOptional: false, useAi: false,
    })).toEqual({ error: '请选择一份已上传的报表' });
  });

  it('persists selections per factory and source, filtering ids no longer eligible', () => {
    saveFactoryChartSelection('F006', 'business', ['yield', 'obsolete']);
    saveFactoryChartSelection('F006', 'upload', ['cost']);
    expect(localStorage.getItem(factoryChartStorageKey('F006', 'business'))).toBe('["yield","obsolete"]');
    expect(loadFactoryChartSelection('F006', 'business', ['yield', 'margin'])).toEqual(['yield']);
    expect(loadFactoryChartSelection('F006', 'upload', ['cost', 'yield'])).toEqual(['cost']);
    expect(loadFactoryChartSelection('F007', 'business', ['yield'])).toEqual(['yield']);
  });

  it('shows at most six eligible charts by default while keeping all ids selectable', () => {
    expect(loadFactoryChartSelection('F006', 'business', ['a', 'b', 'c', 'd', 'e', 'f', 'g'])).toEqual(['a', 'b', 'c', 'd', 'e', 'f']);
  });
  it('never shares one factory selection with another factory', () => {
    saveFactoryChartSelection('F006', 'business', ['yield']);
    saveFactoryChartSelection('F007', 'business', ['margin']);
    expect(loadFactoryChartSelection('F006', 'business', ['yield', 'margin'])).toEqual(['yield']);
    expect(loadFactoryChartSelection('F007', 'business', ['yield', 'margin'])).toEqual(['margin']);
  });
  it('uses readable defaults for a different report without erasing saved intent or an explicit empty choice', () => {
    saveFactoryChartSelection('F006', 'upload', ['old-report']);
    expect(loadFactoryChartSelection('F006', 'upload', ['new-report'])).toEqual(['new-report']);
    expect(localStorage.getItem(factoryChartStorageKey('F006', 'upload'))).toBe('["old-report"]');
    saveFactoryChartSelection('F006', 'upload', []);
    expect(loadFactoryChartSelection('F006', 'upload', ['new-report'])).toEqual([]);
  });
});
