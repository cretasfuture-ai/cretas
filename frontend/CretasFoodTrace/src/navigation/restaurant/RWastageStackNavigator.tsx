import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { RWastageStackParamList } from '../../types/navigation';
import WastageListScreen from '../../screens/restaurant/wastage/WastageListScreen';
import WastageCreateScreen from '../../screens/restaurant/wastage/WastageCreateScreen';
import { screenErrorBoundary } from '../../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<RWastageStackParamList>();

export function RWastageStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <Stack.Screen name="WastageList" component={WastageListScreen} />
      <Stack.Screen name="WastageCreate" component={WastageCreateScreen} />
    </Stack.Navigator>
  );
}

export default RWastageStackNavigator;
