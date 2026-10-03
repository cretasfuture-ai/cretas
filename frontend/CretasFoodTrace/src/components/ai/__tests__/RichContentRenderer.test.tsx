import React from 'react';
import { render, screen } from '@testing-library/react-native';
import { detectRichData, RichContentRenderer } from '../RichContentRenderer';

it('renders stock quantity, batch unit and Chinese status through the actual AI table', () => {
  const data = detectRichData({
    content: [{
      id: 'B-1', factoryId: 'F006', batchNumber: 'MT-1', materialName: '成品盒',
      materialCode: 'BOX', materialCategory: '包装', materialMainCategory: '辅料',
      currentQuantity: 0, quantityUnit: '盒', unit: 'kg', status: 'USED_UP',
    }], totalElements: 1, currentPage: 1, totalPages: 1, hasMore: false,
  });
  expect(data).toBeDefined();
  render(<RichContentRenderer data={data!} />);
  expect(screen.getByText('账面数量')).toBeTruthy();
  expect(screen.getByText('0')).toBeTruthy();
  expect(screen.getByText('盒')).toBeTruthy();
  expect(screen.getByText('已用完')).toBeTruthy();
  expect(screen.queryByText('kg')).toBeNull();
  expect(screen.queryByText('Material Category')).toBeNull();
});
