import { clearSmartbiChartEngineCache, loadSmartbiChartEngine } from '../../../utils/smartbiChartEngine';
import * as FileSystem from 'expo-file-system';
import { Asset } from 'expo-asset';

// Metro registers txt as an asset ID; Jest must not execute the vendor bytes as a Node module.
jest.mock('../../../../assets/smartbi/echarts-6.1.0.min.txt', () => 23);

jest.mock('expo-asset', () => ({ Asset: { fromModule: jest.fn(() => ({ localUri: 'file:///echarts.txt', downloadAsync: jest.fn(async () => ({ localUri: 'file:///echarts.txt' })) })) } }));

describe('smartbiChartEngine', () => {
  beforeEach(() => { clearSmartbiChartEngineCache(); jest.clearAllMocks(); });
  it('loads a nonempty local bundled engine and caches it', async () => {
    (FileSystem.readAsStringAsync as jest.Mock).mockResolvedValue('/* echarts */ ' + 'x'.repeat(2000));
    await expect(loadSmartbiChartEngine()).resolves.toContain('echarts');
    await loadSmartbiChartEngine();
    expect(Asset.fromModule).toHaveBeenCalledTimes(1);
    expect(Asset.fromModule).toHaveBeenCalledWith(23);
    expect(FileSystem.readAsStringAsync).toHaveBeenCalledTimes(1);
  });
  it('clears failed loads and rejects empty or invalid assets', async () => {
    (FileSystem.readAsStringAsync as jest.Mock).mockResolvedValueOnce('');
    await expect(loadSmartbiChartEngine()).rejects.toThrow(/为空或无效/);
    (FileSystem.readAsStringAsync as jest.Mock).mockResolvedValue('/* echarts */ ' + 'x'.repeat(2000));
    await expect(loadSmartbiChartEngine()).resolves.toContain('echarts');
  });
  it('rejects remote URI and download failures before reading or executing engine text', async () => {
    (Asset.fromModule as jest.Mock).mockReturnValueOnce({ downloadAsync: jest.fn(async () => ({ localUri: 'https://invalid.example/engine.txt' })) });
    await expect(loadSmartbiChartEngine()).rejects.toThrow('本地');
    expect(FileSystem.readAsStringAsync).not.toHaveBeenCalled();
    (Asset.fromModule as jest.Mock).mockReturnValueOnce({ downloadAsync: jest.fn().mockRejectedValue(new Error('download failed')) });
    await expect(loadSmartbiChartEngine()).rejects.toThrow('download failed');
    expect(FileSystem.readAsStringAsync).not.toHaveBeenCalled();
  });
});
