import { Asset } from 'expo-asset';
import * as FileSystem from 'expo-file-system';

type AssetLike = { localUri?: string | null; downloadAsync: () => Promise<AssetLike> };
let cached: Promise<string> | null = null;

function assetSource(): AssetLike {
  const moduleId = require('../../assets/smartbi/echarts-6.1.0.min.txt') as number;
  return Asset.fromModule(moduleId);
}

export async function loadSmartbiChartEngine(): Promise<string> {
  if (cached) return cached;
  cached = (async () => {
    const asset = assetSource();
    const downloaded = await asset.downloadAsync();
    const uri = downloaded.localUri;
    if (!uri || !uri.startsWith('file://')) throw new Error('本地图表引擎资源不可用');
    const source = await FileSystem.readAsStringAsync(uri);
    if (source.length < 1000 || !/echarts/i.test(source)) throw new Error('本地图表引擎资源为空或无效');
    return source;
  })().catch(error => {
    cached = null;
    throw error instanceof Error ? error : new Error(String(error));
  });
  return cached;
}

export function clearSmartbiChartEngineCache(): void {
  cached = null;
}
