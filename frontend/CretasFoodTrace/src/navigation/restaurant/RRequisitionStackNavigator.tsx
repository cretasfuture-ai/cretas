import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { RRequisitionStackParamList } from '../../types/navigation';
import RequisitionCreateScreen from '../../screens/restaurant/requisition/RequisitionCreateScreen';
import RequisitionApprovalScreen from '../../screens/restaurant/requisition/RequisitionApprovalScreen';
import RequisitionDetailScreen from '../../screens/restaurant/requisition/RequisitionDetailScreen';
import { screenErrorBoundary } from '../../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<RRequisitionStackParamList>();

export function RRequisitionStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <Stack.Screen name="RequisitionCreate" component={RequisitionCreateScreen} />
      <Stack.Screen name="RequisitionApproval" component={RequisitionApprovalScreen} />
      <Stack.Screen name="RequisitionDetail" component={RequisitionDetailScreen} />
    </Stack.Navigator>
  );
}

export default RRequisitionStackNavigator;
