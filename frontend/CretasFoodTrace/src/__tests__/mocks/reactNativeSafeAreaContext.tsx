import React from 'react';

const insets = { top: 0, right: 0, bottom: 0, left: 0 };

export const SafeAreaProvider = ({ children }: { children?: React.ReactNode }) => <>{children}</>;
export const SafeAreaView = ({ children }: { children?: React.ReactNode }) => <>{children}</>;
export const useSafeAreaInsets = () => insets;
export const useSafeAreaFrame = () => ({ x: 0, y: 0, width: 390, height: 844 });
export const initialWindowMetrics = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets,
};
