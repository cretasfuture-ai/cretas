import React, { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { WebView, type WebViewMessageEvent, type WebViewNavigation } from 'react-native-webview';
import type { FactoryChartPoint } from '../../types/factoryCharts';
import { FACTORY_CHART_RUNTIME } from '../../utils/smartbiFactoryChartRuntime';

const ECHARTS_URL = 'https://cdn.jsdelivr.net/npm/echarts@5.4.3/dist/echarts.min.js';

export interface FactoryChartCanvasProps {
  option: Record<string, unknown>;
  title?: string;
  height?: number;
  onPoint?: (point: FactoryChartPoint) => void;
  onReady?: () => void;
  onError?: (message: string) => void;
  testID?: string;
}

export interface FactoryChartCanvasHandle {
  capturePng: () => Promise<string>;
}

type BridgeMessage =
  | { type: 'ready'; nonce: string }
  | { type: 'point'; nonce: string; point: FactoryChartPoint }
  | { type: 'capture'; nonce: string; requestId: string; dataUrl: string }
  | { type: 'error'; nonce: string; message: string };

function escapeJsonForScript(value: Record<string, unknown>): string {
  return JSON.stringify(value)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/&/g, '\\u0026')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029');
}

export function buildFactoryChartHtml(option: Record<string, unknown>, nonce = 'render-test-nonce'): string {
  const serialized = escapeJsonForScript(option);
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"/><style>html,body,#chart{margin:0;width:100%;height:100%;background:#fff;overflow:hidden}</style></head><body><div id="chart"></div><script src="${ECHARTS_URL}"></script><script>(function(){
  var option=${serialized}, renderNonce=${JSON.stringify(nonce)};
  var chart;
  function post(message){if(window.ReactNativeWebView){window.ReactNativeWebView.postMessage(JSON.stringify(message));}}
  ${FACTORY_CHART_RUNTIME}
  function resolve(value,depth){if(depth>20)return value;if(typeof value==='string'){if(value.indexOf('__FMT__')===0){var f=FMT[value.slice(7)];if(!f)throw new Error('不支持的图表格式化标记：'+value);return f;}if(value.indexOf('__ANIM__')===0){var a=ANIM[value.slice(8)];if(!a)throw new Error('不支持的图表动画标记：'+value);return a;}return value;}if(Array.isArray(value))return value.map(function(v){return resolve(v,depth+1);});if(value&&typeof value==='object'){var out={};Object.keys(value).forEach(function(k){out[k]=resolve(value[k],depth+1);});return out;}return value;}
  function start(){try{if(!window.echarts){throw new Error('图表引擎加载失败');} chart=window.echarts.init(document.getElementById('chart')); chart.setOption(Object.assign({},resolve(option,0),{animation:false}),{notMerge:true,lazyUpdate:false,silent:true}); chart.resize(); chart.on('click',function(params){post({type:'point',nonce:renderNonce,point:{name:String(params.name||''),seriesName:params.seriesName?String(params.seriesName):undefined,dataIndex:Number.isInteger(params.dataIndex)?params.dataIndex:undefined}});}); window.requestAnimationFrame(function(){post({type:'ready',nonce:renderNonce});});}catch(error){post({type:'error',nonce:renderNonce,message:String(error&&error.message||error)});}}
  function capture(requestId){try{if(!chart){throw new Error('图表尚未就绪');}var dataUrl=chart.getDataURL({type:'png',pixelRatio:2,backgroundColor:'#fff'});if(typeof dataUrl!=='string'||!/^data:image\\/png;base64,.+/.test(dataUrl))throw new Error('图表未返回有效PNG');post({type:'capture',nonce:renderNonce,requestId:requestId,dataUrl:dataUrl});}catch(error){post({type:'error',nonce:renderNonce,message:String(error&&error.message||error)});}}
  function receive(event){var value=event.data;try{if(typeof value==='string')value=JSON.parse(value);}catch(e){return;}if(value&&value.type==='capture'&&typeof value.requestId==='string')capture(value.requestId);}
  document.addEventListener('message',receive); window.addEventListener('message',receive); window.addEventListener('resize',function(){if(chart){chart.resize();}}); start();
}());</script></body></html>`;
}

export const FactoryChartCanvas = forwardRef<FactoryChartCanvasHandle, FactoryChartCanvasProps>(function FactoryChartCanvas(
  { option, title, height = 240, onPoint, onReady, onError, testID = 'factory-chart-canvas' },
  ref,
) {
  const webViewRef = useRef<WebView>(null);
  const pendingCapture = useRef<{ requestId: string; resolve: (value: string) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> } | null>(null);
  const captureSequence = useRef(0);
  const renderNonce = useMemo(() => `render-${Date.now()}-${Math.random().toString(36).slice(2)}`, [option]);
  const [ready, setReady] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const html = useMemo(() => buildFactoryChartHtml(option, renderNonce), [option, renderNonce]);

  useEffect(() => {
    setReady(false);
    setLocalError(null);
    return () => {
      const pending = pendingCapture.current;
      if (pending) {
        clearTimeout(pending.timer);
        pendingCapture.current = null;
        pending.reject(new Error('图表已卸载，无法完成图片生成'));
      }
    };
  }, [html]);

  useImperativeHandle(ref, () => ({
    capturePng: () => new Promise<string>((resolve, reject) => {
      if (!ready || !webViewRef.current) {
        reject(new Error('图表尚未就绪，无法导出图片'));
        return;
      }
      if (pendingCapture.current) {
        reject(new Error('图表图片正在生成'));
        return;
      }
      const timer = setTimeout(() => {
        pendingCapture.current = null;
        reject(new Error('图表图片生成超时'));
      }, 5000);
      const requestId = `capture-${++captureSequence.current}`;
      pendingCapture.current = { requestId, resolve, reject, timer };
      webViewRef.current.postMessage(JSON.stringify({ type: 'capture', requestId }));
    }),
  }), [ready]);

  const handleMessage = (event: WebViewMessageEvent) => {
    let message: BridgeMessage;
    try {
      const parsed: unknown = JSON.parse(event.nativeEvent.data);
      if (!parsed || typeof parsed !== 'object' || !('type' in parsed)) return;
      message = parsed as BridgeMessage;
    } catch {
      return;
    }
    if (message.nonce !== renderNonce) return;
    if (message.type === 'ready') {
      setReady(true);
      setLocalError(null);
      onReady?.();
    } else if (message.type === 'point' && message.point && typeof message.point.name === 'string') {
      onPoint?.(message.point);
    } else if (message.type === 'capture' && typeof message.requestId === 'string' && typeof message.dataUrl === 'string' && /^data:image\/png;base64,.+/.test(message.dataUrl)) {
      const pending = pendingCapture.current;
      if (pending && pending.requestId === message.requestId) {
        clearTimeout(pending.timer);
        pendingCapture.current = null;
        pending.resolve(message.dataUrl);
      }
    } else if (message.type === 'error' && typeof message.message === 'string') {
      setReady(false);
      setLocalError(message.message);
      const pending = pendingCapture.current;
      if (pending) {
        clearTimeout(pending.timer);
        pendingCapture.current = null;
        pending.reject(new Error(message.message));
      }
      onError?.(message.message);
    }
  };

  const handleNavigation = (request: WebViewNavigation): boolean => {
    return request.url === 'about:blank' || request.url === 'about:srcdoc';
  };

  return (
    <View testID={testID} style={[styles.container, { height }]}>
      <WebView
        ref={webViewRef}
        originWhitelist={['about:blank']}
        source={{ html, baseUrl: 'about:blank' }}
        onMessage={handleMessage}
        onShouldStartLoadWithRequest={handleNavigation}
        javaScriptEnabled
        domStorageEnabled={false}
        thirdPartyCookiesEnabled={false}
        sharedCookiesEnabled={false}
        allowsInlineMediaPlayback={false}
        scrollEnabled={false}
        onError={(event) => {
          const message = event.nativeEvent.description || '图表加载失败';
          setLocalError(message);
          onError?.(message);
        }}
        accessibilityLabel={title || 'SmartBI图表'}
      />
      {localError && <View style={styles.error}><Text style={styles.errorText}>{localError}</Text></View>}
    </View>
  );
});

const styles = StyleSheet.create({
  container: { overflow: 'hidden', borderRadius: 8, backgroundColor: '#FFFFFF' },
  error: { ...StyleSheet.absoluteFillObject, alignItems: 'center', justifyContent: 'center', padding: 16, backgroundColor: '#FFF5F5' },
  errorText: { color: '#B42318', textAlign: 'center' },
});

export default FactoryChartCanvas;
