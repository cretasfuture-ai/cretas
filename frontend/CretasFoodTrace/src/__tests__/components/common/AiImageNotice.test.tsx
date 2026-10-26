import React from 'react';
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { render } from '@testing-library/react-native';

import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import AiImageNotice from '../../../components/common/AiImageNotice';
import zh from '../../../i18n/locales/zh-CN/common.json';
import en from '../../../i18n/locales/en-US/common.json';

describe('AiImageNotice', () => {
  beforeAll(async () => {
    // 不引 src/i18n (它要原生 expo-localization), 用同一份 common.json 起一个实例
    await i18n.use(initReactI18next).init({
      lng: 'zh-CN', defaultNS: 'common', resources: { 'zh-CN': { common: zh } },
    });
  });

  it('渲染「图片会发给 AI 识别」', () => {
    const { getByTestId, getByText } = render(<AiImageNotice />);
    expect(getByTestId('ai-image-notice')).toBeTruthy();
    expect(getByText('图片会发给 AI 识别')).toBeTruthy();
  });

  it('中英文都有这句', () => {
    expect((zh as Record<string, unknown>).aiImageNotice).toBe('图片会发给 AI 识别');
    expect(typeof (en as Record<string, unknown>).aiImageNotice).toBe('string');
  });
});

/**
 * 拍照 / 从相册选后图片会交给 AI 识别的入口 (2026-10-27 盘点; 餐饮已废弃的送货单入口、
 * 默认不渲染的表单 AI 助手按钮除外)。新增这类入口时把文件加进来; 去掉提示会在这里红。
 */
const AI_PHOTO_ENTRIES = [
  'screens/shared/VisionVerifyScreen.tsx', // 耳标 / 肉块拍照核验
  'screens/shared/LabelScanScreen.tsx', // 标签拍照 → AI 核对
  'screens/factory-admin/device/AIDeviceInputScreen.tsx', // 设备铭牌拍照识别
  'screens/factory-admin/iot/IotDeviceCreateScreen.tsx', // 协议文档拍照识别
  'screens/factory-admin/isapi/IsapiDeviceCreateScreen.tsx', // 摄像头配置标签拍照识别
];

describe('AI 识别拍照入口都带提示', () => {
  it.each(AI_PHOTO_ENTRIES)('%s', (file) => {
    const src = readFileSync(resolve(__dirname, '../../..', file), 'utf-8');
    expect(src).toMatch(/<AiImageNotice[\s/>]/);
    expect(src).toMatch(/import AiImageNotice from '[./]+components\/common\/AiImageNotice';/);
  });
});
