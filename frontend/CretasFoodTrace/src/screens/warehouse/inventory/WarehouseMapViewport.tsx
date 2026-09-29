import React, { useCallback, useEffect, useState } from 'react';
import { LayoutChangeEvent, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Text, TouchableRipple } from 'react-native-paper';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, { useAnimatedStyle, useSharedValue } from 'react-native-reanimated';
import type { WarehouseLayoutConfiguration, WarehouseMapFloor } from '../../../services/api/warehouseLayoutApiClient';
import { WarehouseFloorMap } from './WarehouseFloorMap';
import { warehouseMapExtent } from './warehouseFloorMapModel';
import { boundedMapOffset, pinchMapTransform } from './warehouseMapGestureModel';

interface Props {
  floor: WarehouseMapFloor;
  layout: WarehouseLayoutConfiguration;
  selectedRackId: string;
  onSelectRack: (id: string) => void;
}

export function WarehouseMapViewport({ floor, layout, selectedRackId, onSelectRack }: Props) {
  const extent = warehouseMapExtent(floor);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const viewportWidth = useSharedValue(0);
  const viewportHeight = useSharedValue(0);
  const minimumScale = useSharedValue(1);
  const scale = useSharedValue(1);
  const translateX = useSharedValue(0);
  const translateY = useSharedValue(0);
  const startScale = useSharedValue(1);
  const startX = useSharedValue(0);
  const startY = useSharedValue(0);
  const startFocalX = useSharedValue(0);
  const startFocalY = useSharedValue(0);

  const fit = size.width && size.height
    ? Math.min(size.width / extent.width, size.height / extent.height)
    : 1;
  const cover = size.width && size.height
    ? Math.max(size.width / extent.width, size.height / extent.height)
    : 1;
  const maximumScale = Math.max(3, fit * 4);

  useEffect(() => {
    viewportWidth.value = size.width;
    viewportHeight.value = size.height;
    minimumScale.value = fit;
    scale.value = Math.min(cover, maximumScale);
    translateX.value = 0;
    translateY.value = 0;
  }, [floor.id, extent.width, extent.height, cover, fit, maximumScale, minimumScale, scale, size.height, size.width, translateX, translateY, viewportHeight, viewportWidth]);

  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    setSize((previous) => previous.width === width && previous.height === height ? previous : { width, height });
  }, []);

  const pan = Gesture.Pan()
    .minDistance(6)
    .maxPointers(1)
    .onBegin(() => {
      startX.value = translateX.value;
      startY.value = translateY.value;
    })
    .onUpdate((event) => {
      translateX.value = boundedMapOffset(startX.value + event.translationX, extent.width, viewportWidth.value, scale.value);
      translateY.value = boundedMapOffset(startY.value + event.translationY, extent.height, viewportHeight.value, scale.value);
    });

  const pinch = Gesture.Pinch()
    .onStart((event) => {
      startScale.value = scale.value;
      startX.value = translateX.value;
      startY.value = translateY.value;
      startFocalX.value = event.focalX;
      startFocalY.value = event.focalY;
    })
    .onUpdate((event) => {
      const next = pinchMapTransform(
        { scale: startScale.value, translateX: startX.value, translateY: startY.value },
        { x: startFocalX.value, y: startFocalY.value },
        { x: event.focalX, y: event.focalY },
        event.scale,
        minimumScale.value,
        maximumScale,
        { width: extent.width, height: extent.height },
        { width: viewportWidth.value, height: viewportHeight.value },
      );
      scale.value = next.scale;
      translateX.value = next.translateX;
      translateY.value = next.translateY;
    });

  const mapGesture = Gesture.Simultaneous(pan, pinch);
  const mapStyle = useAnimatedStyle(() => ({
    transform: [
      { translateX: translateX.value },
      { translateY: translateY.value },
      { scale: scale.value },
    ],
  }));

  const zoom = (factor: number) => {
    const nextScale = Math.max(fit, Math.min(maximumScale, scale.value * factor));
    scale.value = nextScale;
    translateX.value = boundedMapOffset(translateX.value, extent.width, size.width, nextScale);
    translateY.value = boundedMapOffset(translateY.value, extent.height, size.height, nextScale);
  };

  const reset = () => {
    scale.value = fit;
    translateX.value = 0;
    translateY.value = 0;
  };

  return (
    <View style={styles.viewport} onLayout={onLayout} testID="warehouse-map-viewport">
      {size.width > 0 && size.height > 0 && (
        <GestureDetector gesture={mapGesture}>
          <View style={StyleSheet.absoluteFill}>
            <Animated.View style={[styles.mapSurface, {
              width: extent.width,
              height: extent.height,
              left: (size.width - extent.width) / 2,
              top: (size.height - extent.height) / 2,
            }, mapStyle]}>
              <WarehouseFloorMap floor={floor} layout={layout} selectedRackId={selectedRackId} onSelectRack={onSelectRack} />
            </Animated.View>
          </View>
        </GestureDetector>
      )}
      <View style={[styles.controls, size.width > size.height && selectedRackId && styles.controlsLeft]}>
        <TouchableRipple onPress={() => zoom(1.5)} style={styles.controlButton}
          accessibilityRole="button" accessibilityLabel="放大地图" testID="warehouse-map-zoom-in">
          <MaterialCommunityIcons name="plus" size={26} color="#1F2937" />
        </TouchableRipple>
        <TouchableRipple onPress={() => zoom(1 / 1.5)} style={styles.controlButton}
          accessibilityRole="button" accessibilityLabel="缩小地图" testID="warehouse-map-zoom-out">
          <MaterialCommunityIcons name="minus" size={26} color="#1F2937" />
        </TouchableRipple>
        <TouchableRipple onPress={reset} style={styles.controlButton}
          accessibilityRole="button" accessibilityLabel="查看完整地图" testID="warehouse-map-reset">
          <MaterialCommunityIcons name="fit-to-screen-outline" size={23} color="#1F2937" />
        </TouchableRipple>
      </View>
      <View pointerEvents="none" style={styles.guide}>
        <Text style={styles.guideText}>单指任意方向拖动 · 双指缩放</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  viewport: { flex: 1, overflow: 'hidden', backgroundColor: '#F5F5F5' },
  mapSurface: { position: 'absolute', backgroundColor: '#fff' },
  controls: { position: 'absolute', right: 16, top: 80, gap: 8 },
  controlsLeft: { right: undefined, left: 16 },
  controlButton: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center', borderRadius: 12, backgroundColor: '#fff', borderWidth: 1, borderColor: '#E5E7EB', elevation: 2 },
  guide: { position: 'absolute', left: 16, right: 80, bottom: 16, alignItems: 'flex-start' },
  guideText: { overflow: 'hidden', paddingHorizontal: 12, paddingVertical: 8, borderRadius: 12, backgroundColor: '#fff', color: '#6B7280', fontSize: 12 },
});
