/**
 * CCP 录入的纯函数 (缺口 13) —— 报工屏与测试共用, 判定口径与后端 CcpMonitoringService 一致 (闭区间)。
 */
import type { CcpReading, CcpStepCheckpoint } from '../../services/api/ccpApi';

/** 输入框文本 → 数值; 空 / 非数字 → null。允许中文全角句点误输入。 */
export function parseCcpValue(text: string | undefined | null): number | null {
  if (text == null) return null;
  const normalized = text.trim().replace('。', '.');
  if (normalized === '' || normalized === '-' || normalized === '.') return null;
  const n = Number(normalized);
  return Number.isFinite(n) ? n : null;
}

/** 超出合格范围 (闭区间 [min, max] 内合格)。 */
export function isCcpOutOfLimit(value: number | null, cp: Pick<CcpStepCheckpoint, 'criticalLimitMin' | 'criticalLimitMax'>): boolean {
  if (value == null) return false;
  return value < Number(cp.criticalLimitMin) || value > Number(cp.criticalLimitMax);
}

export function formatCcpLimit(cp: Pick<CcpStepCheckpoint, 'criticalLimitMin' | 'criticalLimitMax' | 'unit'>): string {
  return `${Number(cp.criticalLimitMin)} ~ ${Number(cp.criticalLimitMax)} ${cp.unit}`;
}

/** 还没填 (或填的不是数字) 的控制点名称。 */
export function missingCcpNames(cps: CcpStepCheckpoint[], values: Record<number, string>): string[] {
  return cps.filter((cp) => parseCcpValue(values[cp.checkpointId]) == null).map((cp) => cp.name);
}

/** 组装提交体; 调用前须 missingCcpNames 为空。 */
export function buildCcpReadings(cps: CcpStepCheckpoint[], values: Record<number, string>): CcpReading[] {
  return cps.map((cp) => ({ checkpointId: cp.checkpointId, measuredValue: parseCcpValue(values[cp.checkpointId]) as number }));
}

export type CcpLoadStatus = 'loading' | 'ready' | 'error';

/**
 * 提交按钮该不该被 CCP 挡住, 挡的原因 (null = 不挡)。
 * 加载中 / 加载失败也挡 —— 不知道要不要录, 就不能当成不用录 (fail-closed)。
 */
export function ccpSubmitBlockReason(status: CcpLoadStatus, cps: CcpStepCheckpoint[], values: Record<number, string>): string | null {
  if (status === 'loading') return '正在加载本道的关键控制点…';
  if (status === 'error') return '关键控制点没加载出来，点「重新加载」后再提交';
  const missing = missingCcpNames(cps, values);
  return missing.length ? `请先填写：${missing.join('、')}` : null;
}
