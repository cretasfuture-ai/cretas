'use strict';

const ROUTES = Object.freeze({
  login: '/login',
  dashboard: '/dashboard',
  bom: '/production/bom',
  purchasing: '/procurement/orders',
  finance: '/finance/ar-ap',
  suppliers: '/procurement/suppliers',
  products: '/system/products',
  workflow: '/system/product-processes',
  productionPlans: '/production/plans',
  bomAchievement: '/production/bom-achievement',
  labelQc: '/quality/label-qc',
  restaurantStaffing: '/restaurant/staffing',
  salesOrders: '/sales/orders',
});

module.exports = { ROUTES };
