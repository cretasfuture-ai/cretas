import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Modal,
  Platform,
  SafeAreaView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { Ionicons } from '@expo/vector-icons';
import { ActivityIndicator, Button, TouchableRipple } from 'react-native-paper';
import Svg, { Line, Polygon } from 'react-native-svg';

import { QI_COLORS } from '../../types/qualityInspector';
import { LabelQcCaptureQualityResult } from '../../services/api/labelQcApi';
import {
  evaluateLabelQcCaptureMetadata,
  LabelQcCaptureAsset,
} from './labelQcCaptureQuality';

interface Props {
  visible: boolean;
  contextLabel: string;
  onClose: () => void;
  onAccepted: (asset: LabelQcCaptureAsset) => void;
  checkQuality: (asset: LabelQcCaptureAsset) => Promise<LabelQcCaptureQualityResult>;
}

const ultraWideLens = (lenses: string[]): string | undefined => lenses.find((lens) => {
  const normalized = lens.toLowerCase().replace(/[\s_-]+/g, '');
  return normalized.includes('ultrawide') || normalized.includes('超广角');
});

export default function QILabelQcCameraModal({
  visible,
  contextLabel,
  onClose,
  onAccepted,
  checkQuality,
}: Props) {
  const [permission, requestPermission] = useCameraPermissions();
  const cameraRef = useRef<CameraView>(null);
  const [availableLenses, setAvailableLenses] = useState<string[]>([]);
  const [useUltraWide, setUseUltraWide] = useState(false);
  const [torch, setTorch] = useState(false);
  const [checking, setChecking] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const ultraWide = useMemo(() => ultraWideLens(availableLenses), [availableLenses]);

  useEffect(() => {
    if (!visible) {
      setChecking(false);
      setErrorMessage(null);
      setTorch(false);
      setUseUltraWide(false);
    }
  }, [visible]);

  const capture = async () => {
    if (!cameraRef.current || checking) return;
    setChecking(true);
    setErrorMessage(null);
    try {
      const photo = await cameraRef.current.takePictureAsync({
        quality: 1,
        base64: false,
        exif: false,
      });
      if (!photo?.uri) {
        setErrorMessage('没有保存到照片，请重新拍摄');
        return;
      }
      const asset: LabelQcCaptureAsset = {
        uri: photo.uri,
        width: photo.width,
        height: photo.height,
        fileName: `label-qc-capture-${Date.now()}.jpg`,
        mimeType: 'image/jpeg',
      };
      const metadata = evaluateLabelQcCaptureMetadata(asset);
      if (!metadata.accepted) {
        setErrorMessage(metadata.reason ?? '照片尺寸不合格，请重新拍摄');
        return;
      }
      const result = await checkQuality(asset);
      if (!result.accepted) {
        setErrorMessage(result.message);
        return;
      }
      onAccepted(asset);
    } catch {
      setErrorMessage('照片检查没有完成，请保持网络连接后重试');
    } finally {
      setChecking(false);
    }
  };

  if (!visible) return null;

  return (
    <Modal
      visible
      animationType="slide"
      presentationStyle="fullScreen"
      onRequestClose={checking ? undefined : onClose}
      testID="qi-label-qc-camera-modal"
    >
      <View style={styles.screen}>
        {permission?.granted ? (
          <>
            <CameraView
              ref={cameraRef}
              style={StyleSheet.absoluteFill}
              facing="back"
              enableTorch={torch}
              ratio={Platform.OS === 'android' ? '4:3' : undefined}
              selectedLens={Platform.OS === 'ios' && useUltraWide ? ultraWide : undefined}
              onAvailableLensesChanged={({ lenses }) => setAvailableLenses(lenses)}
              onMountError={() => setErrorMessage('相机启动失败，请关闭后重试')}
            />

            <SafeAreaView pointerEvents="box-none" style={StyleSheet.absoluteFill}>
              <View style={styles.topBar}>
                <TouchableRipple
                  style={styles.iconButton}
                  onPress={onClose}
                  disabled={checking}
                  borderless
                  accessibilityRole="button"
                  accessibilityLabel="关闭相机"
                  testID="qi-label-qc-camera-close"
                >
                  <Ionicons name="close" size={28} color="#FFFFFF" />
                </TouchableRipple>
                <View style={styles.contextChip}>
                  <Text style={styles.contextText} numberOfLines={1}>{contextLabel}</Text>
                </View>
                <TouchableRipple
                  style={styles.iconButton}
                  onPress={() => setTorch((current) => !current)}
                  disabled={checking}
                  borderless
                  accessibilityRole="button"
                  accessibilityLabel={torch ? '关闭补光灯' : '打开补光灯'}
                  testID="qi-label-qc-camera-flash"
                >
                  <Ionicons name={torch ? 'flash' : 'flash-off'} size={25} color="#FFFFFF" />
                </TouchableRipple>
              </View>

              <View pointerEvents="none" style={styles.guide}>
                <Svg width="100%" height="100%" viewBox="0 0 360 520" preserveAspectRatio="none">
                  <Polygon
                    points="54,10 306,10 353,508 7,508"
                    fill="rgba(0,0,0,0.02)"
                    stroke="#FFFFFF"
                    strokeWidth="3"
                    strokeLinejoin="round"
                  />
                  <Line
                    x1="8"
                    y1="508"
                    x2="352"
                    y2="508"
                    stroke="#20D7A2"
                    strokeWidth="8"
                    strokeLinecap="round"
                  />
                </Svg>
                <View style={styles.guideLabel}>
                  <Text style={styles.guideLabelText}>最下排完整入框</Text>
                </View>
              </View>

              {!!errorMessage && (
                <View style={styles.errorBanner} testID="qi-label-qc-camera-error">
                  <Ionicons name="alert-circle" size={24} color="#FFFFFF" />
                  <Text style={styles.errorBannerText}>{errorMessage}</Text>
                </View>
              )}

              <View style={styles.controls}>
                <View style={styles.sideSlot}>
                  {!!ultraWide && Platform.OS === 'ios' && (
                    <TouchableRipple
                      style={styles.lensButton}
                      onPress={() => setUseUltraWide((current) => !current)}
                      disabled={checking}
                      borderless
                      accessibilityRole="button"
                      accessibilityLabel={useUltraWide ? '切换一倍镜头' : '切换零点五倍广角'}
                      testID="qi-label-qc-camera-lens-toggle"
                    >
                      <Text style={styles.lensText}>{useUltraWide ? '1×' : '0.5×'}</Text>
                    </TouchableRipple>
                  )}
                </View>
                <TouchableRipple
                  style={styles.shutter}
                  onPress={() => void capture()}
                  disabled={checking}
                  borderless
                  accessibilityRole="button"
                  accessibilityLabel="拍照并检查"
                  testID="qi-label-qc-camera-shutter"
                >
                  {checking ? (
                    <ActivityIndicator color={QI_COLORS.primary} size={30} />
                  ) : (
                    <View style={styles.shutterInner} />
                  )}
                </TouchableRipple>
                <View style={styles.sideSlot}>
                  {checking && <Text style={styles.checkingText}>检查中…</Text>}
                </View>
              </View>
            </SafeAreaView>
          </>
        ) : (
          <SafeAreaView style={styles.permission}>
            <Ionicons name="camera-outline" size={58} color={QI_COLORS.disabled} />
            <Text style={styles.permissionTitle}>需要相机权限</Text>
            <Text style={styles.permissionText}>允许相机后即可对准托盘拍摄</Text>
            <Button mode="contained" onPress={() => void requestPermission()} contentStyle={styles.permissionAction}>
              允许相机
            </Button>
            <Button mode="text" onPress={onClose} contentStyle={styles.permissionAction}>
              返回
            </Button>
          </SafeAreaView>
        )}
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#050907' },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingBottom: 12,
    backgroundColor: 'rgba(0,0,0,0.38)',
  },
  iconButton: {
    width: 48,
    height: 48,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
  },
  contextChip: {
    flex: 1,
    minHeight: 38,
    justifyContent: 'center',
    marginHorizontal: 8,
    paddingHorizontal: 12,
    borderRadius: 19,
    backgroundColor: 'rgba(0,0,0,0.52)',
  },
  contextText: { color: '#FFFFFF', textAlign: 'center', fontSize: 13, fontWeight: '700' },
  guide: { position: 'absolute', left: '3%', right: '3%', top: '14%', height: '64%' },
  guideLabel: {
    position: 'absolute',
    alignSelf: 'center',
    bottom: 17,
    paddingHorizontal: 13,
    paddingVertical: 8,
    borderRadius: 18,
    backgroundColor: '#20D7A2',
  },
  guideLabelText: { color: '#07140F', fontSize: 13, fontWeight: '800' },
  errorBanner: {
    position: 'absolute',
    top: '12%',
    left: 14,
    right: 14,
    minHeight: 58,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: 14,
    backgroundColor: 'rgba(174,39,39,0.94)',
  },
  errorBannerText: { flex: 1, color: '#FFFFFF', fontSize: 14, fontWeight: '700', lineHeight: 20 },
  controls: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    minHeight: 142,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
    paddingTop: 22,
    paddingHorizontal: 28,
    backgroundColor: 'rgba(0,0,0,0.58)',
  },
  sideSlot: { width: 72, minHeight: 48, alignItems: 'center', justifyContent: 'center' },
  lensButton: {
    width: 52,
    height: 52,
    borderRadius: 26,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.68)',
  },
  lensText: { color: '#FFFFFF', fontSize: 15, fontWeight: '800' },
  shutter: {
    width: 82,
    height: 82,
    borderRadius: 41,
    borderWidth: 4,
    borderColor: '#FFFFFF',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.08)',
  },
  shutterInner: { width: 64, height: 64, borderRadius: 32, backgroundColor: '#FFFFFF' },
  checkingText: { color: '#FFFFFF', fontSize: 12, fontWeight: '700' },
  permission: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 28 },
  permissionTitle: { marginTop: 16, color: QI_COLORS.text, fontSize: 20, fontWeight: '800' },
  permissionText: { marginTop: 8, marginBottom: 22, color: QI_COLORS.textSecondary, fontSize: 14 },
  permissionAction: { minHeight: 48, minWidth: 160 },
});
