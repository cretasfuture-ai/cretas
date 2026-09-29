import React from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';
import { TouchableRipple } from 'react-native-paper';
import Svg, { Ellipse, Polygon, Polyline, Rect, Text as SvgText } from 'react-native-svg';
import type { WarehouseMapFloor, WarehouseLayoutConfiguration } from '../../../services/api/warehouseLayoutApiClient';
import { warehouseAnnotationPoints, warehouseMapExtent } from './warehouseFloorMapModel';

interface Props {
  floor: WarehouseMapFloor;
  layout: WarehouseLayoutConfiguration;
  selectedRackId: string;
  onSelectRack: (id: string) => void;
}

export function WarehouseFloorMap({ floor, layout, selectedRackId, onSelectRack }: Props) {
  const extent = warehouseMapExtent(floor);
  return (
    <View style={[styles.canvas, extent]} testID="warehouse-floor-map">
      {floor.background ? <>
        <Image source={{ uri: floor.background }} resizeMode="contain" style={StyleSheet.absoluteFillObject} testID="warehouse-map-background" />
        <View pointerEvents="none" style={styles.backgroundWash} />
      </> : null}
      <Svg width={extent.width} height={extent.height} style={styles.layer} pointerEvents="none">
        {(floor.zoneShapes ?? []).map((shape) => {
          const zone = layout.zones.find((item) => item.id === shape.zoneId);
          return <React.Fragment key={shape.zoneId}>
            <Rect x={shape.x} y={shape.y} width={shape.width} height={shape.height} rx={14}
              fill={zone?.color ?? '#E5E7EB'} fillOpacity={0.6} stroke="#94a3b8" strokeDasharray={zone ? '7 5' : '3 3'} strokeWidth={1.5} />
            <SvgText x={shape.x + 12} y={shape.y + 25} fontSize={14} fontWeight="600" fill="#1F2937">
              {zone ? `${zone.prefix} · ${zone.name}` : '已删除的区域'}
            </SvgText>
          </React.Fragment>;
        })}
      </Svg>
      {floor.racks.map((rack) => (
        <TouchableRipple key={rack.id} onPress={() => onSelectRack(rack.id)} rippleColor="rgba(24,144,255,0.16)"
          style={[styles.rack, { left: rack.x, top: rack.y, width: Math.max(44, rack.width), minHeight: Math.max(44, rack.height) }, rack.id === selectedRackId && styles.rackSelected]}
          accessibilityRole="button" accessibilityState={{ selected: rack.id === selectedRackId }}
          accessibilityLabel={`${rack.name}，${rack.levels} 层`} testID={`warehouse-rack-${rack.id}`}>
          <View>
            <Text style={styles.rackName}>{rack.name}</Text>
            <Text style={styles.rackMeta}>{layout.zones.find((zone) => zone.id === rack.zoneId)?.prefix ?? '未归属'} · {rack.levels} 层</Text>
          </View>
        </TouchableRipple>
      ))}
      <Svg width={extent.width} height={extent.height} style={styles.layer} pointerEvents="none">
        {(floor.annotations ?? []).map((annotation) => {
          const stroke = annotation.color ?? '#2563eb';
          const fill = annotation.fill && annotation.fill !== 'none' ? annotation.fill : 'transparent';
          const strokeWidth = annotation.strokeWidth ?? 2;
          if (annotation.kind === 'rect') return <Rect key={annotation.id} x={annotation.x} y={annotation.y} width={annotation.width} height={annotation.height} rx={4} fill={fill} stroke={stroke} strokeWidth={strokeWidth} />;
          if (annotation.kind === 'ellipse') return <Ellipse key={annotation.id} cx={annotation.x + annotation.width / 2} cy={annotation.y + annotation.height / 2} rx={annotation.width / 2} ry={annotation.height / 2} fill={fill} stroke={stroke} strokeWidth={strokeWidth} />;
          if (annotation.kind === 'text') return <SvgText key={annotation.id} x={annotation.x} y={annotation.y + (annotation.fontSize ?? 16)} fontSize={annotation.fontSize ?? 16} fill={annotation.color ?? '#17243a'}>{annotation.text}</SvgText>;
          const points = warehouseAnnotationPoints(annotation);
          if (!points) return null;
          return annotation.kind === 'polygon'
            ? <Polygon key={annotation.id} points={points} fill={fill} stroke={stroke} strokeWidth={strokeWidth} />
            : <Polyline key={annotation.id} points={points} fill="none" stroke={stroke} strokeWidth={strokeWidth} />;
        })}
      </Svg>
    </View>
  );
}

const styles = StyleSheet.create({
  canvas: { position: 'relative', backgroundColor: '#fff' },
  layer: { position: 'absolute', left: 0, top: 0 },
  backgroundWash: { ...StyleSheet.absoluteFillObject, backgroundColor: 'rgba(255,255,255,0.35)' },
  rack: { position: 'absolute', padding: 12, borderWidth: 1, borderColor: '#91CAFF', backgroundColor: '#E6F7FF', borderRadius: 12, overflow: 'hidden', justifyContent: 'center' },
  rackSelected: { borderWidth: 2, borderColor: '#1890FF', backgroundColor: '#D6EEFF' },
  rackName: { color: '#1F2937', fontSize: 16, fontWeight: '700' },
  rackMeta: { color: '#6B7280', fontSize: 13, marginTop: 5 },
});
