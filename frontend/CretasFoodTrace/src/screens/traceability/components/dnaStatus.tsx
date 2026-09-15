import React from 'react';
import type { StyleProp, ViewStyle } from 'react-native';
import { useTranslation } from 'react-i18next';

import zhProcessing from '../../../i18n/locales/zh-CN/processing.json';
import type { AppTheme } from '../../../theme';
import { statusToneColors, ToneChip, type StatusTone, type ToneColors } from './statusTone';

/**
 * DNA 核验状态 —— 文案、含义、下一步与色板的**唯一**出处。
 *
 * 四处显示同一个状态：来源链时间线节点、个体档案头部 chip、后代 / 同父后代行、
 * 拍照核验命中结果；登记表单的四个状态按钮也从这里取文案。2026-09-15 真机实测它们
 * 各有各的长相（时间线是棕字、档案头部是中性描边、后代行是蓝字），2026-09-16 评审又
 * 发现文案有三套（`dnaStatus.tsx` 说「不匹配」、`processing.json` 说「不符」），
 * 表单里选「不符」、档案里看到「不匹配」。所以：
 *
 *   · **文案只有一份**，在 `processing.json` 的 `sourceIndividual.dnaStatus.*`，
 *     本文件只登记**键**（`DNA_STATUS_I18N`），⛔ 不在这里再写一遍中文；
 *   · 每个状态除了标签，还有一句「这意味着什么」（`meaning`）；
 *     MISMATCH 另有一行「下一步」（`nextStep`）—— 它是整条流唯一的坏消息时刻，
 *     只给一个红 chip，老板会当场问「这头牛有问题？」，技术员会想「样本弄混还是系谱填错？」。
 *
 * ⚠️ 未知状态码<b>原样显示那个码</b>、用中性色、没有 meaning，⛔ 不翻译成「未核验」之类
 * 看起来正常的值 —— 把不认识的输入翻译成一个合法值，正是本仓踩过多次的那个坑
 * （`yield_operator` 被渲染成「全无权限」，与真的无权限不可区分）。
 */
export const DNA_STATUS_CODES = ['UNSAMPLED', 'SAMPLED', 'MATCHED', 'MISMATCH'] as const;
export type DnaStatusCode = (typeof DNA_STATUS_CODES)[number];

export function isDnaStatusCode(status: unknown): status is DnaStatusCode {
  return typeof status === 'string' && (DNA_STATUS_CODES as readonly string[]).includes(status);
}

/** `t` 的最小形状 —— 与各屏里 `type T` 一致，`useTranslation('processing')` 给的 `t` 直接能传。 */
export type DnaT = (key: string, options?: Record<string, unknown>) => string;

export interface DnaStatusI18nKeys {
  /** chip / 表单按钮上的短标签。 */
  label: string;
  /** 一句「这意味着什么」。 */
  meaning: string;
  /** 「下一步」—— 只有 MISMATCH 有；其它状态没有要人做的事，⛔ 不硬凑一句。 */
  nextStep: string | null;
}

/**
 * 每个状态在 `processing` 命名空间里的键。表单、档案、时间线都从这张表拿，
 * ⛔ 不各自拼 `sourceIndividual.dnaStatus.${status}` —— 拼出来的字符串一旦有两处就会漂。
 */
export const DNA_STATUS_I18N: Record<DnaStatusCode, DnaStatusI18nKeys> = {
  UNSAMPLED: {
    label: 'sourceIndividual.dnaStatus.UNSAMPLED',
    meaning: 'sourceIndividual.dnaStatus.meaning.UNSAMPLED',
    nextStep: null,
  },
  SAMPLED: {
    label: 'sourceIndividual.dnaStatus.SAMPLED',
    meaning: 'sourceIndividual.dnaStatus.meaning.SAMPLED',
    nextStep: null,
  },
  MATCHED: {
    label: 'sourceIndividual.dnaStatus.MATCHED',
    meaning: 'sourceIndividual.dnaStatus.meaning.MATCHED',
    nextStep: null,
  },
  MISMATCH: {
    label: 'sourceIndividual.dnaStatus.MISMATCH',
    meaning: 'sourceIndividual.dnaStatus.meaning.MISMATCH',
    nextStep: 'sourceIndividual.dnaStatus.nextStep.MISMATCH',
  },
};

/** MISMATCH 说明卡上那个按钮的文案键：「去改登记的父本」。 */
export const DNA_FIX_SIRE_I18N = 'sourceIndividual.dnaStatus.fixSire';

/** 短标签。认不出来的码原样返回；空值返回 null（没登记 DNA 状态不是一种状态）。 */
export function dnaStatusText(t: DnaT, status?: string | null): string | null {
  if (!status) {
    return null;
  }
  return isDnaStatusCode(status) ? t(DNA_STATUS_I18N[status].label) : status;
}

/** 「这意味着什么」。认不出来的码没有含义可说 —— 返回 null，⛔ 不编一句。 */
export function dnaStatusMeaning(t: DnaT, status?: string | null): string | null {
  return isDnaStatusCode(status) ? t(DNA_STATUS_I18N[status].meaning) : null;
}

/** 「下一步」。只有 MISMATCH 有；其它状态与未知码都是 null。 */
export function dnaStatusNextStep(t: DnaT, status?: string | null): string | null {
  if (!isDnaStatusCode(status)) {
    return null;
  }
  const key = DNA_STATUS_I18N[status].nextStep;
  return key ? t(key) : null;
}

/**
 * @deprecated 兼容旧 import 的 zh-CN 静态视图（**不随语言切换**）。它直接从 zh-CN bundle 取，
 * 与 `dnaStatusText` 读的是同一份文案，所以不会漂；但新代码请用 `dnaStatusText(t, status)`。
 */
export const DNA_STATUS_LABEL: Record<string, string> = {
  UNSAMPLED: zhProcessing.sourceIndividual.dnaStatus.UNSAMPLED,
  SAMPLED: zhProcessing.sourceIndividual.dnaStatus.SAMPLED,
  MATCHED: zhProcessing.sourceIndividual.dnaStatus.MATCHED,
  MISMATCH: zhProcessing.sourceIndividual.dnaStatus.MISMATCH,
};

/** @deprecated 同 `DNA_STATUS_LABEL`；新代码用 `dnaStatusText(t, status)`。 */
export function dnaStatusLabel(status?: string | null): string | null {
  if (!status) {
    return null;
  }
  return DNA_STATUS_LABEL[status] ?? status;
}

/**
 * 状态 → 色调。MATCHED 成功、SAMPLED 信息、UNSAMPLED 中性、MISMATCH 错误。
 * ⚠️ 只有这四个键；认不出来的码走 `neutral`（见 `dnaStatusTone`）。
 */
export const DNA_STATUS_TONE: Record<string, StatusTone> = {
  MATCHED: 'success',
  SAMPLED: 'info',
  UNSAMPLED: 'neutral',
  MISMATCH: 'error',
};

export function dnaStatusTone(status?: string | null): StatusTone {
  if (!status) {
    return 'neutral';
  }
  return DNA_STATUS_TONE[status] ?? 'neutral';
}

/** 给测试与非 chip 场景用：某个状态最终落到的底 / 字色。 */
export function dnaStatusColors(theme: AppTheme, status?: string | null): ToneColors {
  return statusToneColors(theme, dnaStatusTone(status));
}

export interface DnaStatusChipProps {
  status?: string | null;
  testID?: string;
  style?: StyleProp<ViewStyle>;
}

/**
 * 四处共用的 DNA 状态 chip。`status` 为空时不渲染 —— 没登记 DNA 状态不是一种状态，
 * ⛔ 不显示一个「未采样」顶上（那会让「没填」和「填了未采样」长得一样）。
 */
export function DnaStatusChip({ status, testID, style }: DnaStatusChipProps) {
  const { t } = useTranslation('processing');
  const label = dnaStatusText(t, status);
  if (!label) {
    return null;
  }
  return (
    <ToneChip tone={dnaStatusTone(status)} testID={testID} style={style}>
      {label}
    </ToneChip>
  );
}
