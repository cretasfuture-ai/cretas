import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { RRecipeStackParamList } from '../../types/navigation';
import RecipeListScreen from '../../screens/restaurant/recipes/RecipeListScreen';
import RecipeDetailScreen from '../../screens/restaurant/recipes/RecipeDetailScreen';
import RecipeEditScreen from '../../screens/restaurant/recipes/RecipeEditScreen';
import { screenErrorBoundary } from '../../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<RRecipeStackParamList>();

export function RRecipeStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <Stack.Screen name="RecipeList" component={RecipeListScreen} />
      <Stack.Screen name="RecipeDetail" component={RecipeDetailScreen} />
      <Stack.Screen name="RecipeEdit" component={RecipeEditScreen} />
    </Stack.Navigator>
  );
}

export default RRecipeStackNavigator;
