import React from 'react';
import vm from 'vm';
import { View } from 'react-native';
import { act, fireEvent, render } from '@testing-library/react-native';
import FactoryChartCanvas, { buildFactoryChartHtml, type FactoryChartCanvasHandle } from '../../../components/smartbi/FactoryChartCanvas';
import { loadSmartbiChartEngine } from '../../../utils/smartbiChartEngine';

jest.mock('../../../utils/smartbiChartEngine', () => ({
  loadSmartbiChartEngine: jest.fn(async () => 'window.echarts = window.echarts || {};'),
}));

const mockPostMessage = jest.fn();
jest.mock('react-native-webview', () => ({
  WebView: React.forwardRef((props: Record<string, unknown>, ref: React.ForwardedRef<{ postMessage: (value: string) => void }>) => {
    React.useImperativeHandle(ref, () => ({ postMessage: mockPostMessage }), []);
    return React.createElement(View, { testID: 'mock-webview', ...props });
  }),
}));

describe('FactoryChartCanvas', () => {
  beforeEach(() => mockPostMessage.mockClear());
  it('uses only the bundled engine while blocking external renderer requests', () => {
    const html = buildFactoryChartHtml({}, 'offline', '/* echarts */ window.engine = "</script><script>bad</script>";');
    expect(html).toContain('default-src \'none\'');
    expect(html).toContain('connect-src \'none\'');
    expect(html).not.toMatch(/<script\s+src=/i);
    expect(html).toContain('<\\/script>');
    expect(html.match(/<\/script>/gi)).toHaveLength(2);
  });
  it('escapes JSON and emits point messages from the bounded bridge', async () => {
    const html = buildFactoryChartHtml({ title: '</script><script>alert(1)</script>', series: [] });
    expect(html).not.toContain('</script><script>alert(1)');
    const onPoint = jest.fn();
    const screen = render(React.createElement(FactoryChartCanvas, { option: { series: [] }, onPoint }));
    await act(async () => { await Promise.resolve(); });
    const nonce = String((screen.getByTestId('mock-webview').props.source.html as string).match(/renderNonce="([^"]+)"/)?.[1]);
    act(() => {
      fireEvent(screen.getByTestId('mock-webview'), 'message', { nativeEvent: { data: JSON.stringify({ type: 'ready', nonce }) } });
      fireEvent(screen.getByTestId('mock-webview'), 'message', { nativeEvent: { data: JSON.stringify({ type: 'point', nonce, point: { name: 'QA直营', seriesName: '收入', dataIndex: 0 } }) } });
    });
    expect(onPoint).toHaveBeenCalledWith({ name: 'QA直营', seriesName: '收入', dataIndex: 0 });
    fireEvent(screen.getByTestId('mock-webview'), 'message', { nativeEvent: { data: JSON.stringify({ type: 'point', nonce, point: { name: 3 } }) } });
    expect(onPoint).toHaveBeenCalledTimes(1);
  });

  it('resolves only a nonempty PNG capture with matching nonce and request id', async () => {
    const ref = React.createRef<FactoryChartCanvasHandle>();
    const screen = render(React.createElement(FactoryChartCanvas, { ref, option: { series: [] } }));
    await act(async () => { await Promise.resolve(); });
    const webview = screen.getByTestId('mock-webview');
    const nonce = String((webview.props.source.html as string).match(/renderNonce="([^"]+)"/)?.[1]);
    fireEvent(webview, 'message', { nativeEvent: { data: JSON.stringify({ type: 'ready', nonce }) } });
    const pending = ref.current!.capturePng();
    const sent = mockPostMessage.mock.calls[mockPostMessage.mock.calls.length - 1]![0] as string;
    const requestId = JSON.parse(sent).requestId;
    fireEvent(webview, 'message', { nativeEvent: { data: JSON.stringify({ type: 'capture', nonce: 'old', requestId, dataUrl: 'data:image/png;base64,old' }) } });
    fireEvent(webview, 'message', { nativeEvent: { data: JSON.stringify({ type: 'capture', nonce, requestId: 'wrong', dataUrl: 'data:image/png;base64,wrong' }) } });
    fireEvent(webview, 'message', { nativeEvent: { data: JSON.stringify({ type: 'capture', nonce, requestId, dataUrl: 'data:image/png;base64,actual' }) } });
    await expect(pending).resolves.toBe('data:image/png;base64,actual');
  });

  it('renders supported formatter markers and rejects unknown markers visibly in the bounded script', () => {
    const html = buildFactoryChartHtml({ yAxis: { axisLabel: { formatter: '__FMT__thousands_sep' } } });
    expect(html).not.toContain('src="http');
    expect(html).toContain('thousands_sep');
    expect(html).toContain('不支持的图表格式化标记');
  });

  it('does not mount a WebView or become ready when the local engine fails', async () => {
    (loadSmartbiChartEngine as jest.Mock).mockRejectedValueOnce(new Error('本地资源失败'));
    const onReady = jest.fn();
    const screen = render(React.createElement(FactoryChartCanvas, { option: { series: [] }, onReady }));
    await act(async () => { await Promise.resolve(); });
    expect(screen.queryByTestId('mock-webview')).toBeNull();
    expect(onReady).not.toHaveBeenCalled();
  });

  it('executes canonical formatters in the bounded HTML without eval', () => {
    let captured: Record<string, unknown> | undefined;
    const messages: string[] = [];
    const html = buildFactoryChartHtml({ yAxis: { axisLabel: { formatter: '__FMT__thousands_sep' }, }, series: [{ label: { formatter: '__FMT__sankey_financial_tooltip' } }, { tooltip: { formatter: '__FMT__financial_rich_tooltip' } }] }, 'vm-nonce');
    const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
    const script = scripts[scripts.length - 1]?.[1];
    expect(script).toBeTruthy();
    const chart = { setOption: (value: Record<string, unknown>) => { captured = value; }, resize: jest.fn(), on: jest.fn(), getDataURL: () => 'data:image/png;base64,valid' };
    const context = { window: { echarts: { init: () => chart }, ReactNativeWebView: { postMessage: (value: string) => messages.push(value) }, requestAnimationFrame: (fn: () => void) => fn(), addEventListener: jest.fn() }, document: { getElementById: () => ({}), addEventListener: jest.fn() } };
    vm.runInNewContext(script!, context);
    const axis = ((captured!.yAxis as Record<string, unknown>).axisLabel as Record<string, unknown>).formatter as (value: number) => string;
    expect(axis(12345)).toBe('1.2万');
    const tooltip = ((captured!.series as Array<Record<string, unknown>>)[0]!.label as Record<string, unknown>).formatter as (value: unknown) => string;
    expect(tooltip({ dataType: 'edge', data: { source: '<来源>', target: '去向', value: 12345 }})).toContain('1.23万元');
    expect(tooltip({ dataType: 'edge', data: { source: '<script>', target: '去向', value: 1 }})).toContain('&lt;script&gt;');
    const rich = ((captured!.series as Array<Record<string, unknown>>)[1]!.tooltip as Record<string, unknown>).formatter as (value: unknown) => string;
    const pie = rich({ componentType: 'series', seriesType: 'pie', seriesName: '费用构成', name: '<材料>', value: 12000, percent: 12.5, color: '#123456' });
    expect(pie).toContain('1.2万');
    expect(pie).toContain('12.5%');
    expect(pie).toContain('&lt;材料&gt;');
    const axisRich = rich([
      { axisValueLabel: '10月', seriesType: 'bar', seriesName: '预算收入', value: 10000 },
      { seriesType: 'bar', seriesName: '实际收入', value: 12000 },
      { seriesType: 'line', seriesName: '同比增长', value: 20 },
    ]);
    expect(axisRich).toContain('20.0%');
    expect(axisRich).toContain('预算差异');
    expect(axisRich).toContain('+20.0%');
    expect(axisRich).toContain('合计');
    expect(axisRich).toContain('2.2万');
    const comparison = rich([{ seriesType: 'bar', seriesName: '本年', value: 120, dataIndex: 1, series: { data: [100, 120, 130] } }, { seriesType: 'bar', seriesName: '上年', value: 100 }]);
    expect(comparison).toContain('同比差异');
    expect(comparison).toContain('<svg');
    expect(messages.some(message => message.includes('"type":"ready"'))).toBe(true);
    const unknownHtml = buildFactoryChartHtml({ formatter: '__FMT__not_supported' }, 'bad-marker');
    const unknownScript = [...unknownHtml.matchAll(/<script>([\s\S]*?)<\/script>/g)].pop()?.[1];
    const unknownMessages: string[] = [];
    const unknownContext = { window: { echarts: { init: () => chart }, ReactNativeWebView: { postMessage: (value: string) => unknownMessages.push(value) }, requestAnimationFrame: (fn: () => void) => fn(), addEventListener: jest.fn() }, document: { getElementById: () => ({}), addEventListener: jest.fn() } };
    vm.runInNewContext(unknownScript!, unknownContext);
    expect(unknownMessages.some(message => message.includes('"type":"error"') && message.includes('不支持的图表格式化标记'))).toBe(true);
  });

  it('does not fabricate a PNG before the mounted chart is ready', async () => {
    const ref = React.createRef<FactoryChartCanvasHandle>();
    const screen = render(React.createElement(FactoryChartCanvas, { ref, option: { series: [] } }));
    await act(async () => { await expect(ref.current?.capturePng()).rejects.toThrow('尚未就绪'); });
    screen.unmount();
  });
});
