import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';
import type { WarehouseLayoutConfiguration } from '../../../../services/api/warehouseLayoutApiClient';

jest.mock('react-native-svg', () => {
  const ReactRuntime = require('react');
  const { View, Text } = require('react-native');
  const shape = (name: string) => (props: Record<string, unknown>) => ReactRuntime.createElement(View, { ...props, testID: `svg-${name}` });
  return {
    __esModule: true,
    default: ({ children }: { children?: React.ReactNode }) => ReactRuntime.createElement(View, null, children),
    Rect: shape('rect'), Ellipse: shape('ellipse'), Polygon: shape('polygon'), Polyline: shape('polyline'),
    Text: ({ children, ...props }: { children?: React.ReactNode }) => ReactRuntime.createElement(Text, { ...props, testID: 'svg-text' }, children),
  };
});

import { WarehouseFloorMap } from '../../../../screens/warehouse/inventory/WarehouseFloorMap';

const layout: WarehouseLayoutConfiguration = {
  zones: [{ id: 'z1', name: '冷藏区', prefix: 'LC', color: '#dbeafe' }],
  locations: [{ id: 'l1', zoneId: 'z1', code: 'LC01', name: '一号位', rackId: 'r1', level: 1 }],
  floors: [{
    id: 'f1', name: '一层', background: 'data:image/png;base64,AAA',
    zoneShapes: [{ zoneId: 'z1', x: 10, y: 20, width: 200, height: 150 }],
    racks: [{ id: 'r1', zoneId: 'z1', name: '一号货架', x: 50, y: 60, width: 120, height: 80, levels: 2 }],
    annotations: [
      { id: 'a1', kind: 'text', x: 30, y: 40, width: 90, height: 30, text: '入口' },
      { id: 'a2', kind: 'polygon', x: 20, y: 30, width: 50, height: 50, points: [0, 0, 50, 0, 50, 50] },
    ],
  }],
};

describe('手机地图真实渲染', () => {
  it('按 Web 布局画出底图、区域、货架和两种标注，并能选择货架', () => {
    const onSelectRack = jest.fn();
    const screen = render(<WarehouseFloorMap floor={layout.floors[0]!} layout={layout} selectedRackId="" onSelectRack={onSelectRack} />);
    expect(screen.getByTestId('warehouse-map-background').props.source.uri).toBe('data:image/png;base64,AAA');
    expect(screen.getAllByTestId('svg-rect')).toHaveLength(1);
    expect(screen.getAllByTestId('svg-polygon')).toHaveLength(1);
    expect(screen.getByText('入口')).toBeTruthy();
    fireEvent.press(screen.getByTestId('warehouse-rack-r1'));
    expect(onSelectRack).toHaveBeenCalledWith('r1');
  });
});
