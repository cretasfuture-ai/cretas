import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { RStocktakingStackParamList } from '../../types/navigation';
import StocktakingListScreen from '../../screens/restaurant/stocktaking/StocktakingListScreen';
import StocktakingExecuteScreen from '../../screens/restaurant/stocktaking/StocktakingExecuteScreen';
import StocktakingSummaryScreen from '../../screens/restaurant/stocktaking/StocktakingSummaryScreen';
import { screenErrorBoundary } from '../../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<RStocktakingStackParamList>();

export function RStocktakingStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <Stack.Screen name="StocktakingList" component={StocktakingListScreen} />
      <Stack.Screen name="StocktakingExecute" component={StocktakingExecuteScreen} />
      <Stack.Screen name="StocktakingSummary" component={StocktakingSummaryScreen} />
    </Stack.Navigator>
  );
}

export default RStocktakingStackNavigator;
