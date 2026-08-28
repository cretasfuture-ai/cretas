import React, { useCallback, useEffect, useState } from 'react';
import Toast from 'react-native-toast-message';
import { AppNavigator } from './src/navigation/AppNavigator';
import { useLanguageStore } from './src/store/languageStore';
import {
  checkAppMinVersion,
  type AppVersionCheckResult,
} from './src/services/appVersionCheck';
import { scheduleBackgroundOtaCheck } from './src/services/otaUpdateService';
import UpdateOverlay from './src/components/common/UpdateOverlay';
import MandatoryUpdateGate from './src/components/common/MandatoryUpdateGate';
import AppErrorBoundary from './src/components/common/AppErrorBoundary';

// 初始化 i18n（必须在 App 组件之前导入）
import './src/i18n';

/**
 * 白垩纪食品溯源系统 - React Native 移动端
 *
 * 功能特性:
 * - 多角色认证系统 / 基于权限的动态导航 / 生物识别 / 自动登录 / 离线 / Token / Toast / i18n
 *
 * OTA 更新策略:
 *   原生启动检查保持 NEVER/0 等待，避免工厂弱网阻塞 splash。
 *   JS 壳启动及回前台主动检查；发现更新后立即提示并并行预下载，
 *   只有用户确认才显示全屏反馈并 reload。
 */
export default function App() {
  const initializeLanguage = useLanguageStore((state) => state.initializeLanguage);
  const [checkingVersion, setCheckingVersion] = useState(true);
  const [versionResult, setVersionResult] = useState<AppVersionCheckResult | null>(null);

  const runVersionCheck = useCallback(async (): Promise<void> => {
    setCheckingVersion(true);
    const result = await checkAppMinVersion();
    setVersionResult(result);
    setCheckingVersion(false);

    if (result.status !== 'update_required') {
      scheduleBackgroundOtaCheck();
    }
  }, []);

  useEffect(() => {
    // 初始化语言设置
    initializeLanguage();
    void runVersionCheck();
  }, [initializeLanguage, runVersionCheck]);

  if (checkingVersion || versionResult?.status === 'update_required') {
    return (
      <MandatoryUpdateGate
        checking={checkingVersion}
        result={
          versionResult?.status === 'update_required' ? versionResult : null
        }
        onRetry={() => void runVersionCheck()}
      />
    );
  }

  return (
    <>
      {/* ⚠️ 边界包住 AppNavigator，不要包住 Toast ——
          兜底 UI 出现时仍然要能弹提示；而 Toast 本身不该把边界拖下水。
          2026-08-28 之前这里没有任何错误边界: 任何一屏的渲染异常都会卸载整棵树,
          用户看到的是一片白, 车间里只能杀进程重开。 */}
      <AppErrorBoundary>
        <AppNavigator />
      </AppErrorBoundary>
      <UpdateOverlay />
      <Toast />
    </>
  );
}
