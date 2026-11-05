/**
 * Factory Admin 管理中心
 * 包含: 员工管理、设备管理等9宫格入口
 */
import React from 'react';
import { View, Text, StyleSheet, ScrollView, TouchableOpacity } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { FAManagementStackParamList } from '../../../types/navigation';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';

type NavigationProp = NativeStackNavigationProp<FAManagementStackParamList, 'FAManagement'>;

interface GridItemProps {
  icon: string;
  title: string;
  color: string;
  onPress: () => void;
  testID?: string;
}

function GridItem({ icon, title, color, onPress, testID }: GridItemProps) {
  return (
    <TouchableOpacity testID={testID} style={styles.gridItem} onPress={onPress}>
      <View style={[styles.gridIcon, { backgroundColor: color + '15' }]}>
        <Icon source={icon} size={28} color={color} />
      </View>
      <Text style={styles.gridTitle}>{title}</Text>
    </TouchableOpacity>
  );
}

export function FAManagementScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t } = useTranslation('home');

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <ScrollView style={styles.scrollView}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>{t('management.title')}</Text>
          <Text style={styles.subtitle}>{t('management.subtitle')}</Text>
        </View>

        {/* 核心管理 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('management.coreManagement')}</Text>
          <View style={styles.grid}>
            <GridItem
              icon="account-group"
              title={t('management.employeeManagement')}
              color="#667eea"
              onPress={() => navigation.navigate('EmployeeList')}
            />
            {/* 设备分析已移除 (2026-06-04): /equipment/analysis/* 后端未实现 (404) */}
            {/* 🔴 2026-08-27: 这里原来有三个 `{!isRestaurantMode && …}` 入口
                （设备中心 / 生产报工 / 工序任务）。本屏只在 FAManagementStackNavigator 里注册，
                而那棵子树只在 isRestaurant(user) 为真时挂载 ⇒ 这三个入口**从没显示过**，
                它们指向的路由也在同一个恒假分支里注册（已一并删除）。
                ⛔ 不要加回来：条件恒假，加回来只是又藏起三个走不到的入口。
                去处：设备台账 web-admin /equipment/list；报工审批 /production/approval；
                工序任务在 ProcessingStackNavigator / WS 栈里本来就可达。 */}
            <GridItem
              icon="domain"
              title={t('management.departmentManagement')}
              color="#fa8c16"
              onPress={() => navigation.navigate('DepartmentManagement')}
            />
          </View>
        </View>

        {/* 进销存管理 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('management.inventoryManagement', '进销存管理')}</Text>
          <View style={styles.grid}>
            <GridItem
              icon="cart-arrow-down"
              title={t('management.purchaseOrders', '采购订单')}
              color="#409eff"
              onPress={() => navigation.navigate('PurchaseOrderList')}
            />
            <GridItem
              icon="cart-arrow-up"
              title={t('management.salesOrders', '销售订单')}
              color="#67c23a"
              onPress={() => navigation.navigate('SalesOrderList')}
            />
            <GridItem
              icon="package-variant-closed"
              title={t('management.finishedGoods', '成品库存')}
              color="#722ed1"
              onPress={() => navigation.navigate('FinishedGoodsList')}
            />
            <GridItem
              icon="swap-horizontal-bold"
              title={t('management.transfer', '调拨管理')}
              color="#fa8c16"
              onPress={() => navigation.navigate('TransferList')}
            />
            <GridItem
              icon="cash-multiple"
              title={t('management.arAp', '应收应付')}
              color="#eb2f96"
              onPress={() => navigation.navigate('ArApOverview')}
            />
            <GridItem
              icon="tag-text"
              title={t('management.priceList', '价格表')}
              color="#13c2c2"
              onPress={() => navigation.navigate('PriceList')}
            />
            <GridItem
              icon="keyboard-return"
              title={t('management.returns', '退货管理')}
              color="#f56c6c"
              onPress={() => navigation.navigate('ReturnOrderList')}
            />
            <GridItem
              icon="cash-check"
              title="录入收款"
              color="#52c41a"
              onPress={() => navigation.navigate('PaymentRecord' as never)}
            />
            <GridItem
              icon="flask-outline"
              title="研发需求"
              color="#722ed1"
              onPress={() => navigation.navigate('RdRequestCreate' as never)}
            />
            {/* SP6 #36 — 出纳付款 + 采购异常 入口 */}
            <GridItem
              icon="bank-transfer-out"
              title="出纳付款"
              color="#1B65A8"
              onPress={() => navigation.navigate('CashierPaymentList' as never)}
            />
            <GridItem
              icon="alert-circle-outline"
              title="采购异常"
              color="#C62828"
              onPress={() => navigation.navigate('PurchaseExceptionList' as never)}
            />
          </View>
        </View>

        {/* 系统配置分区已移除 (2026-06-04): 表单配置/页面编辑器/表单模版/规则配置/
            AI智能初始化/编码规则/质检项配置/SOP配置/AI意图查看 均为管理员在 web 后台
            使用的配置工具, 不适合手机端 (路由仍在 navigator 注册, 仅从菜单隐藏). */}

        {/* 其他 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('management.other')}</Text>
          <View style={styles.grid}>
            <GridItem
              icon="delete-outline"
              title={t('management.disposalRecordManagement')}
              color="#8c8c8c"
              onPress={() => navigation.navigate('DisposalRecordManagement')}
            />
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  scrollView: {
    flex: 1,
  },
  header: {
    padding: 20,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#333',
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    marginTop: 4,
  },
  section: {
    marginTop: 16,
    paddingHorizontal: 16,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#666',
    marginBottom: 12,
    marginLeft: 4,
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 8,
  },
  gridItem: {
    width: '33.33%',
    alignItems: 'center',
    paddingVertical: 16,
  },
  gridIcon: {
    width: 56,
    height: 56,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8,
  },
  gridTitle: {
    fontSize: 13,
    color: '#333',
  },
});

export default FAManagementScreen;
