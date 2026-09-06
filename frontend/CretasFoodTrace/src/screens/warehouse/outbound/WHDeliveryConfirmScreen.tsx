/**
 * 发货确认 (真发货 DLV-*) — 填实际发货数量 → 后端扣减成品库存 + 转 SHIPPED.
 *
 * 防呆 (六扇门 F006 仓管场景):
 *  - Rule 1 上限预显: 每行实发数量 input :max = 计划数量, 超限 disable 提交, 不事后报错.
 *  - Rule 2 上下文: 顶部带 单号 / 客户 / 销售单 / 计划数量, 每行带品名, 仓管一眼看清发什么.
 *  - 4 位一体: 确认失败原样透传后端 message (如批次未分配 409), sticky 显示, 不吞成"操作失败".
 *  - Alert 陷阱: 确认写操作由页面按钮直接触发, **不** 藏在 Alert.alert 回调里 (Expo web 上失效).
 */

import React, { useState, useCallback, useEffect } from 'react';
import { View, ScrollView, StyleSheet, TouchableOpacity } from 'react-native';
import { Text, Surface, Button, TextInput, Snackbar, ActivityIndicator, Portal, Dialog } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { isAxiosError } from 'axios';
import { WHOutboundStackParamList } from '../../../types/navigation';
import {
  warehouseDeliveryApiClient,
  WarehouseDeliveryRecord,
  WarehouseDeliveryItem,
} from '../../../services/api/warehouseDeliveryApiClient';
import { handleError } from '../../../utils/errorHandler';
import { validateQty, canSubmitRows, buildActualQuantities, shortfallOf } from './warehouseDeliveryConfirm.logic';
import { salesDeliveryBatchAllocationApiClient } from '../../../services/api/salesDeliveryBatchAllocationApiClient';
import { isConfirmableDeliveryStatus } from '../warehouseList.logic';
import { MissingRouteParams } from '../../../components/common/MissingRouteParams';

type NavigationProp = NativeStackNavigationProp<WHOutboundStackParamList, 'WHDeliveryConfirm'>;
type ScreenRoute = RouteProp<WHOutboundStackParamList, 'WHDeliveryConfirm'>;

interface EditableItem {
  id: string;
  productName: string;
  plannedQty: number;
  unit: string;
  /** 用户输入的实发数量 (字符串, 允许编辑中间态). */
  actualQtyText: string;
  /** 产品名回落显示 + 查取货批次。 */
  productTypeId: string;
  /**
   * 本次系统会从哪几个批次取货(按先进先出算出来的)。
   * ⚠️ 这是**信息不是决定** —— 仓管不需要选, 但需要知道该搬哪一批。
   */
  pickBatches?: { batchNumber: string; productionDate?: string; qty: number }[];
  /**
   * 进屏时算出来的缺口（需要的量 − 系统现在能凑出来的量），凑得够时为 undefined。
   *
   * ⛔ 有缺口**不 disable 提交按钮**。`recommendFifo` 是进屏那一刻的快照，
   * 库存随时可能被别人动；硬拦会造出「明明发得了却不让发」的误伤，
   * 而后端才是权威（它会 409）。这里只负责**事先告诉他**，把「点了才知道」
   * 变成「进门就知道」，出路仍然是同一个「去调拨」。
   */
  shortfallQty?: number;
}

function itemDisplayName(it: WarehouseDeliveryItem): string {
  return it.productName || `产品-${it.productTypeId || ''}`;
}

export function WHDeliveryConfirmScreen() {
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<ScreenRoute>();
  // ⛔ 原来是 `= route.params;` —— 没有 params 时直接抛
  //    `Cannot destructure property 'deliveryId' of 'route.params' as it is undefined`。
  //    2026-08-28 表单走查实测这一屏当场崩, 由 AppErrorBoundary 接住。
  //    正常入口都传了参数, 所以是潜伏的 —— 但崩溃和「说清缺什么」代价差得很远。
  const { deliveryId, deliveryNumber } = route.params ?? ({} as { deliveryId?: string; deliveryNumber?: string });

  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [record, setRecord] = useState<WarehouseDeliveryRecord | null>(null);
  const [items, setItems] = useState<EditableItem[]>([]);
  const [snackbar, setSnackbar] = useState<{ visible: boolean; message: string; error: boolean }>({
    visible: false,
    message: '',
    error: false,
  });

  const loadDetail = useCallback(async () => {
    setLoading(true);
    try {
      const res = await warehouseDeliveryApiClient.getDeliveryDetail(deliveryId);
      if (!res.success || !res.data) {
        handleError(new Error(res.message || '加载发货单明细失败'), { title: '加载失败' });
        return;
      }
      setRecord(res.data);
      const readOnlyHistory = !isConfirmableDeliveryStatus(res.data.status);
      const detailItems = Array.isArray(res.data.items) ? res.data.items : [];
      const mapped: EditableItem[] = detailItems.map((it) => {
        const planned = Number(it.deliveredQuantity) || 0;
        return {
          id: String(it.id ?? ''),
          productName: itemDisplayName(it),
          plannedQty: planned,
          unit: it.unit || 'kg',
          actualQtyText: String(planned), // 默认实发 = 计划
          productTypeId: it.productTypeId || '',
        };
      });
      setItems(mapped);
      // 🔴 2026-08-20: 算出「本次从哪几个批次取货」并显示出来。
      //
      // 起因: 8-20 一单实测账实分家 —— 系统按先进先出扣了 08-19 的老批次,
      // 而仓管拿的是当天 10:31 刚生产的那 4 盒。**系统做的是对的**(食品先进先出防过期),
      // 落差在于**没人告诉他该拿哪一批**。
      //
      // ⇒ 这里只做一件事: 把系统的决定显示出来, 让他拿对货。
      // ⛔ 不给他选择权(那就退回到「分配批次」那套被否掉的复杂度),
      // ⛔ 也不额外要求任何操作 —— 零点击。
      void (async () => {
        try {
          const withPicks = await Promise.all(mapped.map(async (row) => {
            if (readOnlyHistory) {
              const saved = await salesDeliveryBatchAllocationApiClient.list(row.id);
              const picks = (saved?.data ?? [])
                .map((allocation) => ({
                  batchNumber: String(allocation.batchNumber ?? ''),
                  qty: Number(allocation.allocatedQty ?? 0),
                }))
                .filter((pick) => pick.batchNumber && pick.qty > 0);
              return { ...row, pickBatches: picks };
            }
            if (!row.productTypeId) return row;
            const need = Number(row.actualQtyText) || row.plannedQty;
            const rec = await salesDeliveryBatchAllocationApiClient.recommendFifo(row.id, {
              productTypeId: row.productTypeId, requiredQty: need, unit: row.unit,
            });
            const picks = (rec?.data ?? [])
              .map((c) => ({
                batchNumber: String(c.batchNumber ?? ''),
                productionDate: c.productionDate ? String(c.productionDate) : undefined,
                qty: Number(c.recommendedQuantity ?? 0),
              }))
              .filter((p) => p.batchNumber && p.qty > 0);
            // 🔴 2026-08-21: 够不够, **进屏这一刻就知道** —— 这些 picks 加起来就是
            // 系统现在能凑出来的量。原来只拿它显示「该搬哪一批」, 够不够没人看,
            // 于是仓管仍然是**点了才被后端 409 挡回来**, 只是挡回来之后多了条「去调拨」。
            // ⇒ 事实在手里就该事先说 (防呆 Rule 1: 预先显示边界, 不要事后报错)。
            // ⛔ 判断本身放在 logic 文件里(纯函数)而不是写在这儿 —— 实测扫源码的闸
            //    量不到它(见 shortfallOf 的说明), 判据必须落在可测的行为上。
            return { ...row, pickBatches: picks, shortfallQty: shortfallOf(need, picks) };
          }));
          setItems(withPicks);
        } catch {
          // 取货提示拿不到不该阻断发货 —— 它是锦上添花, ⛔ 不是前置条件。
          // (静默是**故意的**: 这里 loud 一次只会让他以为发不了货。)
        }
      })();
    } catch (error) {
      handleError(error, { title: '加载发货单明细失败' });
    } finally {
      setLoading(false);
    }
  }, [deliveryId]);

  useEffect(() => {
    loadDetail();
  }, [loadDetail]);

  const setItemQty = (id: string, text: string) => {
    setItems((prev) => prev.map((it) => (it.id === id ? { ...it, actualQtyText: text } : it)));
  };

  const firstError = items
    .map((it) => validateQty(it.actualQtyText, it.plannedQty))
    .find((e) => e != null);
  const hasError = firstError != null;
  const anyChanged = items.some((it) => Number(it.actualQtyText) !== it.plannedQty);
  const readOnlyHistory = Boolean(record && !isConfirmableDeliveryStatus(record.status));
  const canSubmit = !readOnlyHistory && !loading && !submitting && canSubmitRows(items);

  // 库存不足时的出路对话框(⛔ 不是只弹一句他做不到的话)
  const [shortageDialogVisible, setShortageDialogVisible] = useState(false);
  /**
   * 去调拨 —— **同栈**跳转。
   *
   * 2026-08-21 之前这里是跨栈的 (`parent.navigate('WHHomeTab', {screen:'TransferList'})`),
   * 因为 TransferList 当时挂在首页栈上。同日首页 tab 被收敛掉, 那个跳转会落到一个
   * 不再存在的 tab 上 —— 于是 TransferList/TransferDetail 一并迁进了发货栈。
   * ⇒ 现在是同栈直跳, 不再依赖 getParent(), 也不会因为 tab 增减而失效。
   */
  const goToTransfer = useCallback(() => {
    setShortageDialogVisible(false);
    navigation.navigate('TransferList');
  }, [navigation]);

  // 确认写操作 — 页面按钮直接调用, 不经 Alert 回调 (Expo web 上 Alert 回调失效).
  const submitConfirm = async () => {
    if (!canSubmit) return;
    const actualQuantities = buildActualQuantities(items);
    setSubmitting(true);
    try {
      const res = await warehouseDeliveryApiClient.confirmDelivery(deliveryId, actualQuantities);
      if (res.success) {
        setSnackbar({ visible: true, message: '发货确认成功，成品库存已扣减', error: false });
        // 让用户看到成功提示后返回列表 (列表 focus 时自动刷新, 该单从待确认队列消失)
        setTimeout(() => navigation.goBack(), 900);
      } else {
        setSnackbar({ visible: true, message: res.message || '发货确认失败', error: true });
      }
    } catch (error) {
      // 4 位一体: 原样透传后端 message。
      let message = '发货确认失败，请重试';
      let stockShortage = false;
      if (isAxiosError(error)) {
        const data = error.response?.data as { message?: string; actionHint?: string } | undefined;
        if (data?.message) {
          message = data.actionHint ? `${data.message}（${data.actionHint}）` : data.message;
          // 🔴 2026-08-20: 库存不足这一条要给**手机上走得通**的下一步。
          // 原来只弹一句「请先入库补货，或从其它库区调拨」——话是对的，但他点不到调拨。
          // (Steve: 「总不能他所有的功能都是通过网页端做的吧，首页端也要支持直接去调拨」)
          stockShortage = data.message.includes('可用库存不足');
        }
      }
      if (stockShortage) {
        setSnackbar({ visible: true, message, error: true });
        setShortageDialogVisible(true);
      } else {
        setSnackbar({ visible: true, message, error: true });
      }
    } finally {
      setSubmitting(false);
    }
  };

  // 没有发货确认对象就没法继续 —— 说清该从哪进来, ⛔ 不要崩。
  if (!deliveryId) {
    return <MissingRouteParams title="发货确认" hint="这一页要从「待发货」列表里点某一张发货单进来。" onBack={() => navigation.goBack()} />;
  }

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Header title="发货确认" onBack={() => navigation.goBack()} />
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载发货单明细...</Text>
        </View>
      </SafeAreaView>
    );
  }

  const customerName = record?.customerName || record?.customerId || '未知客户';
  const orderNumber = record?.orderNumber || record?.salesOrderId || '无关联订单';

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Header title={readOnlyHistory ? '发货记录' : '发货确认'} onBack={() => navigation.goBack()} />

      <ScrollView style={styles.content} keyboardShouldPersistTaps="handled">
        {/* Rule 2: 上下文 — 单号 / 客户 / 销售单 */}
        <Surface style={styles.card} elevation={1}>
          <Text style={styles.cardTitle}>{record?.deliveryNumber || deliveryNumber || `DLV-${deliveryId}`}</Text>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>客户</Text>
            <Text style={styles.infoValue}>{customerName}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>销售单</Text>
            <Text style={styles.infoValue}>{orderNumber}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>计划日期</Text>
            <Text style={styles.infoValue}>{record?.deliveryDate || '-'}</Text>
          </View>
          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>当前状态</Text>
            <Text style={styles.infoValue}>{record?.status || '-'}</Text>
          </View>
          {!!record?.logisticsCompany && (
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>物流公司</Text>
              <Text style={styles.infoValue}>{record.logisticsCompany}</Text>
            </View>
          )}
          {!!record?.trackingNumber && (
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>物流单号</Text>
              <Text style={styles.infoValue}>{record.trackingNumber}</Text>
            </View>
          )}
          {readOnlyHistory && (
            <>
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>操作者</Text>
                <Text style={styles.infoValue}>{record?.shippedBy ? `用户 ${record.shippedBy}` : '系统未记录'}</Text>
              </View>
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>完成时间</Text>
                <Text style={styles.infoValue}>{record?.updatedAt || '-'}</Text>
              </View>
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>下一步</Text>
                <Text style={styles.infoValue}>只读留档；签收状态由销售端继续跟进</Text>
              </View>
            </>
          )}
        </Surface>

        {/* 提示: 默认按计划, 改动需重新分配批次 */}
        <Surface style={styles.warnCard} elevation={1}>
          <MaterialCommunityIcons name={readOnlyHistory ? 'check-circle-outline' : 'alert-outline'} size={18} color={readOnlyHistory ? '#2e7d32' : '#f57c00'} />
          <Text style={styles.warnText}>
            {readOnlyHistory
              ? '这是已完成的发货记录。数量和实际出库批次只读展示，不会再次扣减库存。'
              : '请填写每行实际发货数量（默认 = 计划数量）。确认后系统扣减成品库存。'}
            {!readOnlyHistory && anyChanged ? ' 已修改数量 —— 系统会按实发数量重新核对批次。' : ''}
          </Text>
        </Surface>

        {/* 🔴 未分配批次 —— 事先说清, 而不是让人点了才被后端 409 挡回来。
            ⚠️ 只 disable 按钮是**另一种「点不动」**: 用户看不出为什么。所以必须同时给出原因和去处。 */}
        {/*
          🔴 2026-08-20 owner 拍板: 「批次」这个概念从 App 上整个拿掉。
          原来这里是一张红卡: 「还有 N 行未分配批次, 现在还不能确认发货」+ 分配按钮。

          owner 原话:「批次他们自己都管不过来, 我还得帮他录入」——
          追溯改为按**订单号/销售单**做, 不依赖批次粒度。
          ⇒ 后端在确认发货时**自动按先进先出分配**(SalesServiceImpl 的 P0-13 处),
            仓管只需要回答一个问题: 这单实发多少。

          ⚠️ 自动分配**不丢数据**: HACCP 放行闸与成本核算读的都是「已分配的批次」,
          不关心是人点的还是系统算的。
          ⚠️ 库存真不够时后端仍然拦, 并直接说「可用库存不足, 请先补货或调拨」——
          那句话仓管看得懂, 也做得到, ⛔ 不再是「去网页端点分配批次」。
        */}

        {/* Rule 1: 每行实发数量, 计划作上限 */}
        <View style={styles.itemsWrap}>
          {items.length === 0 ? (
            <Surface style={styles.card} elevation={1}>
              <Text style={styles.emptyText}>此发货单无明细行</Text>
            </Surface>
          ) : (
            items.map((it) => {
              const err = validateQty(it.actualQtyText, it.plannedQty);
              return (
                <Surface key={it.id} style={styles.itemCard} elevation={1}>
                  <Text style={styles.itemName}>{it.productName}</Text>
                  <View style={styles.itemQtyRow}>
                    <View style={styles.plannedBox}>
                      <Text style={styles.plannedLabel}>计划数量</Text>
                      <Text style={styles.plannedValue}>
                        {it.plannedQty} {it.unit}
                      </Text>
                    </View>
                    <View style={styles.actualBox}>
                      {readOnlyHistory ? (
                        <View style={styles.plannedBox}>
                          <Text style={styles.plannedLabel}>实际发货</Text>
                          <Text style={styles.plannedValue}>{it.actualQtyText} {it.unit}</Text>
                        </View>
                      ) : (
                      <TextInput
                        mode="outlined"
                        label={`实发 (最多 ${it.plannedQty})`}
                        value={it.actualQtyText}
                        onChangeText={(t) => setItemQty(it.id, t)}
                        keyboardType="numeric"
                        error={err != null}
                        right={<TextInput.Affix text={it.unit} />}
                        style={styles.qtyInput}
                        contentStyle={styles.qtyInputContent}
                        testID={`wh-actual-qty-${it.id}`}
                      />
                      )}
                      {!!err && <Text style={styles.errText}>{err}</Text>}
                    </View>
                  </View>
                  {/*
                    取货批次 —— 告诉他该搬哪一批, ⛔ 不是让他选。
                    系统按先进先出算(食品行业正确做法: 老货先出防过期);
                    2026-08-20 实测过一次账实分家, 成因就是**没人告诉他拿哪一批**。
                  */}
                  {it.pickBatches && it.pickBatches.length > 0 && (
                    <View style={styles.pickBox} testID={`wh-pick-batches-${it.id}`}>
                      <View style={styles.pickHeader}>
                        <MaterialCommunityIcons name="package-variant" size={14} color="#409eff" />
                        <Text style={styles.pickTitle}>{readOnlyHistory ? '实际出库批次：' : '请从这些货里取：'}</Text>
                      </View>
                      {it.pickBatches.map((p) => (
                        <Text key={p.batchNumber} style={styles.pickLine}>
                          {`· ${p.batchNumber}`}
                          {p.productionDate ? `（${p.productionDate} 生产）` : ''}
                          {` ${p.qty} ${it.unit}`}
                        </Text>
                      ))}
                    </View>
                  )}
                  {/*
                    库存不够 —— **进屏就说**, ⛔ 不是等他填完点了才被后端 409 挡回来。
                    这个事实在 recommendFifo 返回那一刻就在手里(picks 加起来 < 需要的量),
                    原来只用它显示「搬哪一批」, 够不够没人看。
                    ⛔ 不 disable 提交: 快照可能过期, 硬拦会误伤(见 shortfallQty 的说明)。
                  */}
                  {it.shortfallQty != null && (
                    <View style={styles.shortBox} testID={`wh-shortfall-${it.id}`}>
                      <View style={styles.pickHeader}>
                        <MaterialCommunityIcons name="alert-circle-outline" size={14} color="#e6a23c" />
                        <Text style={styles.shortTitle}>
                          {`库存可能不够：还差约 ${it.shortfallQty} ${it.unit}`}
                        </Text>
                      </View>
                      <Text style={styles.shortHint}>
                        可以先去调拨补货；也可以直接确认，以仓库实际扣减为准。
                      </Text>
                      <Button
                        mode="text"
                        compact
                        onPress={goToTransfer}
                        testID={`wh-shortfall-go-transfer-${it.id}`}
                      >
                        去调拨
                      </Button>
                    </View>
                  )}
                </Surface>
              );
            })
          )}
        </View>

        <View style={{ height: 100 }} />
      </ScrollView>

      {/* 底部确认按钮 — 直接触发写操作, 不藏 Alert 回调 */}
      {!readOnlyHistory && <Surface style={styles.footer} elevation={4}>
        <Button
          mode="contained"
          onPress={submitConfirm}
          disabled={!canSubmit}
          loading={submitting}
          style={styles.confirmButton}
          contentStyle={styles.confirmButtonContent}
          labelStyle={styles.confirmButtonLabel}
          buttonColor="#4CAF50"
          testID="wh-confirm-delivery-btn"
        >
          {submitting ? '确认中...' : '确认并扣库存'}
        </Button>
        {hasError && <Text style={styles.footerHint}>请修正数量后再确认（{firstError}）</Text>}
      </Surface>}

      <Snackbar
        visible={snackbar.visible}
        onDismiss={() => setSnackbar((s) => ({ ...s, visible: false }))}
        duration={snackbar.error ? 8000 : 2000}
        action={snackbar.error ? { label: '关闭', onPress: () => setSnackbar((s) => ({ ...s, visible: false })) } : undefined}
        style={snackbar.error ? styles.snackbarError : styles.snackbarSuccess}
      >
        {snackbar.message}
      </Snackbar>

      {/*
        🔴 2026-08-20: 库存不足时给一个**手机上走得通**的下一步。
        原来只弹一句「请先入库补货，或从其它库区调拨」—— 话是对的，但他点不到调拨
        (Steve:「总不能所有功能都通过网页端做吧，首页端也要支持直接去调拨」)。
        ⛔ 不自作主张替他发起调拨: 从哪个库区调、调多少是他的判断, 这里只负责把他送过去。
      */}
      <Portal>
        <Dialog visible={shortageDialogVisible} onDismiss={() => setShortageDialogVisible(false)}>
          <Dialog.Title>库存不够，发不了</Dialog.Title>
          <Dialog.Content>
            <Text style={{ color: '#606266', lineHeight: 20 }}>
              仓库里这个产品的可用数量不够本次发货。可以先从其它库区调拨补货，回来再确认发货。
            </Text>
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setShortageDialogVisible(false)}>知道了</Button>
            <Button mode="contained" onPress={goToTransfer} testID="wh-confirm-go-transfer">
              去调拨
            </Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>
    </SafeAreaView>
  );
}

function Header({ title, onBack }: { title: string; onBack: () => void }) {
  return (
    <View style={styles.header}>
      <TouchableOpacity style={styles.backButton} onPress={onBack} testID="wh-confirm-back">
        <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
      </TouchableOpacity>
      <Text style={styles.headerTitle}>{title}</Text>
      <View style={{ width: 24 }} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: {
    backgroundColor: '#4CAF50',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  backButton: { padding: 2 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  content: { flex: 1 },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 16, margin: 16, marginBottom: 0 },
  cardTitle: { fontSize: 16, fontWeight: 'bold', color: '#333', marginBottom: 12 },
  infoRow: { flexDirection: 'row', marginBottom: 8 },
  infoLabel: { width: 72, fontSize: 13, color: '#999' },
  infoValue: { flex: 1, fontSize: 13, color: '#333', fontWeight: '500' },
  blockCard: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
    padding: 12,
    marginHorizontal: 12,
    marginBottom: 8,
    borderRadius: 8,
    backgroundColor: '#ffebee',
  },
  blockText: { flex: 1, fontSize: 13, lineHeight: 20, color: '#c62828' },
  // R-3 F3: 触摸目标 ≥ 44pt（仓管员戴手套、光线差，小按钮点不中）
  blockPrimaryBtn: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6,
    minHeight: 44, marginTop: 10, paddingHorizontal: 12, borderRadius: 6,
    backgroundColor: '#c62828',
  },
  blockPrimaryText: { fontSize: 13, fontWeight: '700', color: '#fff' },
  blockActionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    minHeight: 44,
    marginTop: 10,
    paddingHorizontal: 12,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: '#c62828',
    backgroundColor: '#fff',
  },
  blockActionText: { fontSize: 13, fontWeight: '600', color: '#c62828' },
  warnCard: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    backgroundColor: '#fff3e0',
    marginHorizontal: 16,
    marginTop: 16,
    padding: 12,
    borderRadius: 8,
    gap: 8,
  },
  warnText: { flex: 1, fontSize: 12, color: '#e65100', lineHeight: 18 },
  itemsWrap: { paddingHorizontal: 16, paddingTop: 16 },
  itemCard: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 12 },
  pickBox: { marginTop: 12, paddingTop: 10, borderTopWidth: 1, borderTopColor: '#ebeef5' },
  pickHeader: { flexDirection: 'row', alignItems: 'center', gap: 4, marginBottom: 4 },
  pickTitle: { fontSize: 12, fontWeight: '600', color: '#409eff' },
  // 库存缺口提示 —— 用琥珀色而不是红色: 这是「可能不够」的提醒, 不是「不许发」的拒绝。
  // ⛔ 颜色要跟语义一致, 红色会被读成硬拦, 而这里刻意不拦。
  shortBox: { marginTop: 10, paddingTop: 8, borderTopWidth: 1, borderTopColor: '#faecd8' },
  shortTitle: { fontSize: 12, fontWeight: '600', color: '#e6a23c' },
  shortHint: { fontSize: 11, color: '#909399', marginTop: 2, lineHeight: 16 },
  pickLine: { fontSize: 12, lineHeight: 19, color: '#606266' },
  itemName: { fontSize: 15, fontWeight: '600', color: '#333', marginBottom: 12 },
  itemQtyRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  plannedBox: { flex: 1 },
  plannedLabel: { fontSize: 12, color: '#999' },
  plannedValue: { fontSize: 18, fontWeight: 'bold', color: '#333', marginTop: 4 },
  actualBox: { flex: 1.4 },
  qtyInput: { backgroundColor: '#fff' },
  // 实发数量是仓管在这一屏唯一要填的数字, 也是最容易看错的 —— 放大字号 (对应 fool-proof
  // 数字输入大字号约束, WHReceiptCreateScreen 的数量框已经是这个字号, 这里补齐同一口径)。
  qtyInputContent: { fontSize: 24 },
  errText: { fontSize: 11, color: '#f44336', marginTop: 2 },
  emptyText: { fontSize: 14, color: '#999', textAlign: 'center' },
  footer: {
    backgroundColor: '#fff',
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 20,
  },
  confirmButton: { borderRadius: 8 },
  confirmButtonContent: { height: 48 },
  confirmButtonLabel: { fontSize: 16, fontWeight: '600' },
  footerHint: { fontSize: 12, color: '#f44336', textAlign: 'center', marginTop: 8 },
  snackbarError: { backgroundColor: '#c62828' },
  snackbarSuccess: { backgroundColor: '#2e7d32' },
});

export default WHDeliveryConfirmScreen;
