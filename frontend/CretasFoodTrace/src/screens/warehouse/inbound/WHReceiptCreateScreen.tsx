/**
 * W-ABA-1 Day 5 — PDF 扫码后收货录入页 (2 字段闭环).
 *
 * 场景: 仓管员扫 PDF 二维码 → WHScanOperation 拿到 orderNumber → 调 by-number
 * 接口 → 跳本页 (purchaseOrderId / orderNumber). 本页 prefill 全部信息, 仓管员只
 * 录"收货数量 + 商品日期"两个字段, 抄码品行的数量改成实际称重.
 *
 * 客户原话 (六扇门 May 7 part 2):
 * - "做仓管的他年纪都比较大文化素质很低的"
 * - "对于那个仓管员来说他的任务很简单就是我核对数量核对那个商品的日期这两个"
 * - "其他的话就尽量少让那个仓管员去参与什么什么价格类的"
 * - L177-180: "拍照也可以留个单谱吧, 就是你留个附件类似一个拍照然后一个附件吗也可以的呀"
 *
 * 提交流程:
 *   1. POST /purchase/receives  (创建草稿入库单, 含全部 items)
 *   2. Issue #794: 把拍好的照片上传到 OSS, 注册 entityType=PURCHASE_RECEIPT, entityId=receive.id
 *      ⛔ 必须在 confirm 【之前】—— 后端 confirm 会校验该入库单已有凭证, 否则 409
 *   3. POST /purchase/receives/{id}/confirm  (确认 → 触发 material_batches 创建)
 *   4. 对每个抄码品行: POST /material/abaca-log (用 batchNumber 自动解析 batchId)
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  Alert,
  Image,
  KeyboardAvoidingView,
  Platform,
  TouchableOpacity,
} from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Chip,
  Menu,
  Portal,
  Dialog,
  RadioButton,
  Text,
  TextInput,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system';

import { WHInboundStackParamList } from '../../../types/navigation';
import {
  purchaseApiClient,
  PurchaseOrder,
  PurchaseOrderItem,
  PurchaseReceiveRecord,
  PurchaseReceiveItem,
  CreateReceiveRecordRequest,
} from '../../../services/api/purchaseApiClient';
import {
  materialTypeApiClient,
  MaterialType,
} from '../../../services/api/materialTypeApiClient';
import { abacaApiClient } from '../../../services/api/abacaApiClient';
import { attachmentApi } from '../../../services/api/attachmentApi';
import { materialBatchApiClient } from '../../../services/api/materialBatchApiClient';
import {
  warehouseReceivingApiClient,
  type PurchaseReceivingCloseReason,
} from '../../../services/api/warehouseReceivingApiClient';
import {
  materialPackagingApiClient,
  MaterialPackagingHierarchy,
} from '../../../services/api/materialPackagingApiClient';
import { handleError } from '../../../utils/errorHandler';
import { useAuthStore } from '../../../store/authStore';

type Nav = NativeStackNavigationProp<WHInboundStackParamList, 'WHReceiptCreate'>;
type RouteProps = RouteProp<WHInboundStackParamList, 'WHReceiptCreate'>;

interface RowDraft {
  receivedQuantity: string;
  productionDate: string;       // YYYY-MM-DD
  materialPackagingSpecId: string;
  unit: string;
  baseUnit: string;
  factor: number;
}

// Issue #794: 收货拍照 — 提交前拍, 建完草稿后【确认之前】批量上传到 entity=PURCHASE_RECEIPT
interface PendingPhoto {
  uri: string;
  fileName: string;
  mimeType: string;
  size: number;
}

const todayStr = (): string => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

export default function WHReceiptCreateScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute<RouteProps>();
  const { purchaseOrderId, orderNumber } = route.params;
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [order, setOrder] = useState<PurchaseOrder | null>(null);
  const [materialsById, setMaterialsById] = useState<Record<string, MaterialType>>({});
  const [packagingByMaterial, setPackagingByMaterial] = useState<Record<string, MaterialPackagingHierarchy | null>>({});
  const [packagingMenuItemId, setPackagingMenuItemId] = useState<string | null>(null);
  const [rows, setRows] = useState<Record<string, RowDraft>>({});
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  // 提前关单对话框
  const [closeDialogVisible, setCloseDialogVisible] = useState(false);
  const [closeReason, setCloseReason] = useState<PurchaseReceivingCloseReason>('SUPPLIER_SHORT_SHIPMENT');
  const [closeNotes, setCloseNotes] = useState('');
  const [closing, setClosing] = useState(false);
  // Issue #794: 提交前 stash 照片, 草稿建好后、confirm 之前批量上传
  const [photos, setPhotos] = useState<PendingPhoto[]>([]);
  // SP4: 可选录入厂号/产地 (入库时记录, 提交后写入批次)
  const [factoryNumber, setFactoryNumber] = useState('');
  const [originPlace, setOriginPlace] = useState('');
  // 2026-08-21: 默认收起 —— 这是"仅录数量+日期"这个核心任务之外的可选项,
  // 折叠掉能让他先看到真正要填的东西, 需要追溯来源时再点开(防呆: 每屏只暴露当前步骤需要的信息)。
  const [traceInfoExpanded, setTraceInfoExpanded] = useState(false);

  // ============================================================
  // 加载 PO + 关联 MaterialType (含 abaca flag)
  // ============================================================
  useEffect(() => {
    (async () => {
      try {
        const orderRes = await purchaseApiClient.getOrder(purchaseOrderId, factoryId);
        const o = orderRes?.data;
        if (!o || !o.items || o.items.length === 0) {
          Alert.alert('订单异常', '此采购单无明细, 无法收货', [
            { text: '返回', onPress: () => navigation.goBack() },
          ]);
          return;
        }
        setOrder(o);

        // prefill 每行: 收货数量默认 = 订单数量 - 已收数量, 商品日期默认今天
        const initRows: Record<string, RowDraft> = {};
        for (const it of o.items) {
          const remaining = Math.max(0, (it.quantity || 0) - (it.receivedQuantity || 0));
          initRows[it.id] = {
            receivedQuantity: remaining > 0 ? String(remaining) : '',
            productionDate: todayStr(),
            materialPackagingSpecId: it.materialPackagingSpecId || '',
            unit: it.purchasePackageUnitSnapshot || it.unit,
            baseUnit: it.inventoryBaseUnitSnapshot || it.unit,
            factor: Number(it.packageToBaseFactorSnapshot || 1),
          };
        }
        setRows(initRows);

        // 并行拉每个原料的 MaterialType (拿 abaca 标记)
        const uniqueTypeIds = Array.from(new Set(o.items.map((i) => i.materialTypeId)));
        const typesArr = await Promise.all(
          uniqueTypeIds.map((id) =>
            materialTypeApiClient
              .getMaterialTypeById(id, factoryId)
              .catch(() => null),
          ),
        );
        const map: Record<string, MaterialType> = {};
        typesArr.forEach((t, idx) => {
          const typeId = uniqueTypeIds[idx];
          if (t && typeId) map[typeId] = t;
        });
        setMaterialsById(map);
        const packagingArr = await Promise.all(
          uniqueTypeIds.map((id) => materialPackagingApiClient.getByMaterial(id, factoryId).catch(() => null)),
        );
        const packagingMap: Record<string, MaterialPackagingHierarchy | null> = {};
        packagingArr.forEach((value, idx) => {
          const typeId = uniqueTypeIds[idx];
          if (typeId) packagingMap[typeId] = value;
        });
        setPackagingByMaterial(packagingMap);
      } catch (err) {
        handleError(err, { title: '加载采购订单失败' });
      } finally {
        setLoading(false);
      }
    })();
  }, [purchaseOrderId, factoryId, navigation]);

  // ============================================================
  // 工具
  // ============================================================
  const isAbacaItem = useCallback(
    (item: PurchaseOrderItem): boolean =>
      !!materialsById[item.materialTypeId]?.isAbacaPackaging,
    [materialsById],
  );

  const unitFor = useCallback(
    (item: PurchaseOrderItem): string => {
      const m = materialsById[item.materialTypeId];
      if (m?.isAbacaPackaging) return m.abacaDefaultUnit || 'kg';
      return rows[item.id]?.unit || item.purchasePackageUnitSnapshot || item.unit || m?.unit || '';
    },
    [materialsById, rows],
  );

  const updateRow = (itemId: string, patch: Partial<RowDraft>) => {
    setRows((prev) => ({
      ...prev,
      [itemId]: {
        ...(prev[itemId] || {
          receivedQuantity: '',
          productionDate: todayStr(),
          materialPackagingSpecId: '',
          unit: '',
          baseUnit: '',
          factor: 1,
        }),
        ...patch,
      },
    }));
  };

  // ============================================================
  // Issue #794: 拍照附件
  // ============================================================
  const guessExt = (uri: string, mime?: string): string => {
    if (mime?.startsWith('image/')) return mime.replace('image/', '');
    const dot = uri.lastIndexOf('.');
    return dot > 0 ? uri.substring(dot + 1) : 'jpg';
  };

  const addPhotoFromAsset = useCallback(async (asset: ImagePicker.ImagePickerAsset) => {
    let size = asset.fileSize ?? 0;
    if (!size) {
      const info = await FileSystem.getInfoAsync(asset.uri, { size: true });
      size = info.exists && 'size' in info ? (info.size as number) : 0;
    }
    const fileName = asset.fileName
      ?? `receipt_${Date.now()}.${guessExt(asset.uri, asset.mimeType)}`;
    const mimeType = asset.mimeType ?? 'image/jpeg';
    setPhotos((prev) => [...prev, { uri: asset.uri, fileName, mimeType, size }]);
  }, []);

  const takePhoto = useCallback(async () => {
    const perm = await ImagePicker.requestCameraPermissionsAsync();
    if (!perm.granted) {
      Alert.alert('需要相机权限', '请在设置中开启相机权限以拍照存档');
      return;
    }
    const result = await ImagePicker.launchCameraAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
      quality: 0.7,
    });
    if (!result.canceled && result.assets[0]) {
      await addPhotoFromAsset(result.assets[0]);
    }
  }, [addPhotoFromAsset]);

  const pickPhotoFromGallery = useCallback(async () => {
    const perm = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!perm.granted) {
      Alert.alert('需要相册权限');
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
      quality: 0.7,
      allowsMultipleSelection: true,
    });
    if (!result.canceled) {
      for (const a of result.assets) {
        await addPhotoFromAsset(a);
      }
    }
  }, [addPhotoFromAsset]);

  const removePhoto = (idx: number) => {
    setPhotos((prev) => prev.filter((_, i) => i !== idx));
  };

  // ============================================================
  // 提交
  // ============================================================
  const handleSubmit = useCallback(async () => {
    if (!order || !order.items) return;

    // 校验: 至少 1 行有 quantity > 0
    const validItems = order.items.filter((it) => {
      const q = Number(rows[it.id]?.receivedQuantity);
      return Number.isFinite(q) && q > 0;
    });
    if (validItems.length === 0) {
      Alert.alert('提示', '请至少填写一行收货数量');
      return;
    }

    // 校验: 所有有数量的行必须有商品日期
    for (const it of validItems) {
      const d = rows[it.id]?.productionDate;
      if (!d || !/^\d{4}-\d{2}-\d{2}$/.test(d)) {
        Alert.alert('提示', `行 "${it.materialName || it.materialTypeId}" 商品日期格式不对, 应为 YYYY-MM-DD`);
        return;
      }
      const row = rows[it.id];
      const orderRemaining = Math.max(0, Number(it.quantity || 0) - Number(it.receivedQuantity || 0));
      const maxInSelectedPackage = orderRemaining * Number(it.packageToBaseFactorSnapshot || 1)
        / Number(row?.factor || 1);
      if (Number(row?.receivedQuantity || 0) > maxInSelectedPackage + 1e-9) {
        Alert.alert(
          '超过可收数量',
          `${it.materialName || it.materialTypeId} 本次最多可收 ${maxInSelectedPackage} ${row?.unit || unitFor(it)}`,
        );
        return;
      }
    }

    /*
     * 🔴 2026-08-16: 没有凭证就别让他填完再被拒。
     *
     * 后端 PurchaseServiceImpl 在 confirmReceive 里【无条件】要求该入库单已有
     * PURCHASE_RECEIPT 附件, 否则 409「确认收货前必须上传供应商供货单或收货凭证」
     * (该闸 2026-07-22 #1577 加入, 没有工厂级开关)。
     * web-admin 一直是对的 —— 附件数为 0 时直接禁用「确认收货入库」按钮。
     * 这里同口径地在提交前拦住, 并说清该做什么。
     */
    if (photos.length === 0) {
      Alert.alert(
        '请先拍收货凭证',
        '确认入库前必须有供应商供货单或收货凭证。\n请在上方「收货照片」处拍照或选图后再提交。',
      );
      return;
    }

    setSubmitting(true);
    try {
      // 1. createReceive (草稿)
      const receivePayload: CreateReceiveRecordRequest = {
        purchaseOrderId: order.id,
        supplierId: order.supplierId,
        receiveDate: todayStr(),
        remark: orderNumber ? `扫码入库: ${orderNumber}` : '扫码入库',
        items: validItems.map((it) => {
          const row = rows[it.id];
          if (!row) throw new Error(`收货行缺失: ${it.materialName || it.materialTypeId}`);
          return {
            purchaseOrderItemId: Number(it.id),
            materialTypeId: it.materialTypeId,
            materialPackagingSpecId: row.materialPackagingSpecId || undefined,
            materialName: it.materialName,
            receivedQuantity: Number(row.receivedQuantity),
            unit: row.unit || unitFor(it),
            unitPrice: it.unitPrice,
          };
        }),
      };
      const createRes = await purchaseApiClient.createReceive(receivePayload, factoryId);
      const receive: PurchaseReceiveRecord = createRes.data;
      if (!receive?.id) throw new Error('createReceive 返回缺 id');

      /*
       * 2. Issue #794 + 2026-08-16 顺序修正: 凭证必须在 confirm 【之前】上传。
       *
       * 此前这一步排在 confirm 之后(原第 4 步), 而后端从 2026-07-22 (#1577) 起
       * 在 confirm 里要求附件已存在 ⇒ 第 2 步必然 409, 抛异常后第 4 步根本执行不到,
       * 照片永远传不上去。**RN 扫码入库因此 100% 失败, 持续 25 天** ——
       * 没人发现是因为这条链在生产上没人走(2026-08-16 端到端走查时撞出来的)。
       *
       * 附件要挂在入库单上, 所以必须在 createReceive 拿到 id 之后、confirm 之前。
       */
      let photosUploaded = 0;
      const photoErrors: string[] = [];
      for (const p of photos) {
        try {
          await attachmentApi.uploadAndRegister(
            { uri: p.uri, name: p.fileName, type: p.mimeType, size: p.size },
            'PURCHASE_RECEIPT',
            receive.id,
            { businessTag: 'RECEIVE_PHOTO', fileCategory: 'PHOTO' },
            factoryId,
          );
          photosUploaded += 1;
        } catch (err: unknown) {
          const msg = err instanceof Error ? err.message : '照片上传失败';
          photoErrors.push(`${p.fileName}: ${msg}`);
        }
      }
      // 一张都没传上去就别去 confirm —— 那一定是 409, 而且草稿会停在半路。
      // 说清「草稿已建、去哪继续」, 不要只报一句失败。
      if (photosUploaded === 0) {
        throw new Error(
          `收货凭证一张都没上传成功，未执行入库确认。\n`
          + `入库单草稿 ${receive.receiveNumber || receive.id} 已创建，可在仓储收货任务里补传凭证后确认。\n`
          + photoErrors.join('\n'),
        );
      }

      // 3. confirmReceive (触发 material_batches 创建)
      const confirmRes = await purchaseApiClient.confirmReceive(receive.id, factoryId);
      const confirmed: PurchaseReceiveRecord = confirmRes.data;

      // SP4: 写入厂号/产地 (非必填, 有值时 patch 每个批次)
      if (factoryNumber.trim() || originPlace.trim()) {
        const batchMetaUpdate = {
          ...(factoryNumber.trim() ? { factoryNumber: factoryNumber.trim() } : {}),
          ...(originPlace.trim() ? { originPlace: originPlace.trim() } : {}),
        };
        for (const item of (confirmed.items || [])) {
          if (item.batchNumber) {
            try {
              // 先按 batchNumber 查出批次 UUID, 再 patch
              const listRes = await materialBatchApiClient.getMaterialBatches({ batchNumber: item.batchNumber, factoryId });
              const batchId = listRes?.data?.content?.[0]?.id;
              if (batchId) {
                await materialBatchApiClient.updateBatch(batchId, batchMetaUpdate, factoryId);
              }
            } catch {
              // 非阻塞: 厂号/产地写入失败不影响入库主流程
            }
          }
        }
      }

      // 3. 对每个抄码品行 → POST /material/abaca-log (用 batchNumber 自动 lookup batchId)
      const abacaItems = validItems.filter((it) => isAbacaItem(it));
      const confirmedItemsByMaterial = new Map<string, PurchaseReceiveItem>();
      (confirmed.items || []).forEach((ri) => confirmedItemsByMaterial.set(ri.materialTypeId, ri));

      let abacaLogged = 0;
      const abacaErrors: string[] = [];
      for (const it of abacaItems) {
        const confirmedItem = confirmedItemsByMaterial.get(it.materialTypeId);
        if (!confirmedItem?.batchNumber) {
          abacaErrors.push(`${it.materialName || it.materialTypeId}: 确认后未拿到 batchNumber`);
          continue;
        }
        try {
          const row = rows[it.id];
          if (!row) throw new Error(`收货行缺失: ${it.materialName || it.materialTypeId}`);
          await abacaApiClient.create(
            {
              batchNumber: confirmedItem.batchNumber,
              rawMaterialTypeId: it.materialTypeId,
              actualWeight: Number(row.receivedQuantity),
              unit: unitFor(it),
              weighingMethod: 'MANUAL',
              purchaseOrderItemId: it.id,
              notes: `扫码入库自动落账; 商品日期 ${row.productionDate}`,
            },
            factoryId,
          );
          abacaLogged += 1;
        } catch (err: unknown) {
          const msg = err instanceof Error ? err.message : '抄码日志失败';
          abacaErrors.push(`${it.materialName || it.materialTypeId}: ${msg}`);
        }
      }

      // (凭证已在 confirm 之前上传, 见上方第 2 步 —— ⛔ 不要再挪回这里)

      const summary = [
        `入库单 ${confirmed.receiveNumber || receive.id} 创建并确认成功`,
        `共 ${validItems.length} 行`,
        abacaItems.length > 0
          ? `抄码品自动落称重日志: ${abacaLogged}/${abacaItems.length}`
          : null,
        photos.length > 0
          ? `照片上传: ${photosUploaded}/${photos.length}`
          : null,
      ]
        .filter(Boolean)
        .join('\n');
      const errLines: string[] = [];
      if (abacaErrors.length > 0) {
        errLines.push(`抄码日志失败 (可手工补录):\n- ${abacaErrors.join('\n- ')}`);
      }
      if (photoErrors.length > 0) {
        errLines.push(`照片上传失败:\n- ${photoErrors.join('\n- ')}`);
      }
      const finalMsg = errLines.length > 0 ? `${summary}\n\n${errLines.join('\n\n')}` : summary;

      Alert.alert('入库成功', finalMsg, [
        { text: '确定', onPress: () => navigation.goBack() },
      ]);
    } catch (err) {
      handleError(err, { title: '入库提交失败' });
    } finally {
      setSubmitting(false);
    }
  }, [order, rows, orderNumber, factoryId, navigation, unitFor, isAbacaItem, photos, factoryNumber, originPlace]);

  // ============================================================
  // Render
  // ============================================================
  const totalReceivedRows = useMemo(
    () =>
      Object.values(rows).filter((r) => {
        const q = Number(r.receivedQuantity);
        return Number.isFinite(q) && q > 0;
      }).length,
    [rows],
  );

  /**
   * 是否**所有**收货行都已收满（remaining <= 0）。
   *
   * ⚠️ 与下面 isShortReceipt / handleSubmit 的越界校验用同一个 `quantity - receivedQuantity`
   * 口径（订单单位，不做包装换算）——这里只需要判断"还差不差"，三处漂开会出现
   * 「这里说收满了、那里说还差」。
   */
  const allLinesReceived = useMemo(() => {
    const items = order?.items ?? [];
    if (items.length === 0) return false;
    return items.every((it) => {
      const remaining = Math.max(0, Number(it.quantity || 0) - Number(it.receivedQuantity || 0));
      return remaining <= 1e-9;
    });
  }, [order]);

  /**
   * 这张单还能不能收货 —— 扫码进本页时先判一次，不要让仓管填完表单才被后端拒。
   *
   * 🔴 2026-08-20 六扇门真机实测（PO-20260820-0007）：订单 2000kg，分两次各收 1000
   * 收满后，仓管再扫一次同一张 PDF 二维码，`WHScanOperationScreen.resolveScan` 只按
   * `orderNumber` 查到订单就直接 `navigation.navigate("WHReceiptCreate", ...)`，
   * 不检查订单状态；本页原样渲染整张录入表单，仓管填完数量点提交才在后端拿到 409。
   * ⚠️ 「选单收货」列表页（WHPurchaseReceiveListScreen）本来就会把 COMPLETED/CLOSED/
   * CANCELLED 过滤掉（同文件顶部注释「不需要收货」），扫码入口绕开了这道过滤——
   * 这里补上同一道闸。
   *
   * 双重判据（任一为真即拦）：
   *   · order.status 已是终态（COMPLETED/CLOSED/CANCELLED）
   *   · allLinesReceived —— 兜底防 status 字段还没来得及同步（eventual consistency）；
   *     直接从行级数字算，不依赖这个可能滞后的派生字段
   */
  const orderClosedOut = order != null
    && (order.status === 'CLOSED' || order.status === 'CANCELLED');
  const orderFullyReceived = order != null
    && (order.status === 'COMPLETED' || allLinesReceived);
  const orderNotReceivable = orderClosedOut || orderFullyReceived;

  /**
   * 本次录入是否**短收**（合计少于本单还应收的量）。
   *
   * ⚠️ 换算口径与 handleSubmit 的校验完全一致（选中包装 → 基础单位），
   * ⛔ 不另写一套 —— 两处口径漂开会出现「按钮说短收、提交却说收满了」。
   */
  const isShortReceipt = useMemo(() => {
    const items = order?.items ?? [];
    if (items.length === 0) return false;
    let anyEntered = false;
    let allSatisfied = true;
    for (const it of items) {
      const row = rows[it.id];
      const orderRemaining = Math.max(0, Number(it.quantity || 0) - Number(it.receivedQuantity || 0));
      if (orderRemaining <= 0) continue;               // 这行已经收满
      const enteredInBase = Number(row?.receivedQuantity || 0) * Number(row?.factor || 1);
      if (enteredInBase > 0) anyEntered = true;
      if (enteredInBase + 1e-9 < orderRemaining) allSatisfied = false;
    }
    return anyEntered && !allSatisfied;
  }, [order, rows]);

  /**
   * 提前关单 —— 短收时主动结束这一单。
   *
   * 业务规则（Steve 2026-08-20 口述，与 web 端一致）：
   *   · 没达到预计数量 → 单子**一直开着**，可以继续收
   *   · 达到或超过     → 后端自动关闭
   *   · 少于且不打算再收 → **要人显式点一次**，并说明原因
   *
   * ⚠️ 原因用选择而不是自由文本（防呆 Rule 3），选「其他」才要求补充说明 —— 与 web 同规则。
   *
   * 🔴 ⛔ **不要用 `Alert.prompt` 收那段说明** —— 它是 **iOS 独有**的，
   *    仓管用的是安卓，在他们机器上什么都不会发生。
   *    本仓已经为这一条建过闸（warehouseDeadEndsContract：「手动输入」用了 Alert.prompt）。
   *    ⇒ 用 Paper 的 Dialog，两端一致。
   */
  const CLOSE_REASONS: { code: PurchaseReceivingCloseReason; label: string }[] = [
    { code: 'SUPPLIER_SHORT_SHIPMENT', label: '供应商少发' },
    { code: 'QUALITY_REJECTION', label: '质量不合格拒收' },
    { code: 'PURCHASE_BALANCE_CANCELLED', label: '采购余量已取消' },
    { code: 'DEMAND_CHANGED', label: '需求变更' },
    { code: 'OTHER', label: '其他原因' },
  ];

  const doCloseShort = useCallback(async () => {
    if (closeReason === 'OTHER' && !closeNotes.trim()) {
      Alert.alert('提示', '选择「其他原因」时必须填写补充说明');
      return;
    }
    setClosing(true);
    try {
      const res = await warehouseReceivingApiClient.closePurchaseReceivingTask(
        purchaseOrderId,
        { reasonCode: closeReason, notes: closeNotes.trim() || undefined },
      );
      if (res?.success) {
        setCloseDialogVisible(false);
        Alert.alert(
          '已关单',
          res.data === 'COMPLETED'
            ? `「${orderNumber}」已收满，按正常完成处理。`
            : `「${orderNumber}」已提前关闭，不再接收剩余数量。`,
          [{ text: '知道了', onPress: () => navigation.goBack() }],
        );
      }
    } catch (err) {
      handleError(err, { title: '提前关单失败' });
    } finally {
      setClosing(false);
    }
  }, [closeReason, closeNotes, purchaseOrderId, orderNumber, navigation]);

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="扫码入库录入" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载采购单中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (!order) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="扫码入库录入" />
        </Appbar.Header>
        <View style={styles.center}>
          <Text>采购单加载失败</Text>
        </View>
      </SafeAreaView>
    );
  }

  // 防呆 Rule 1（预先显示边界，不要事后报错）：这单已经收不了了 —— 在这里当场说清楚，
  // ⛔ 不进录入表单，别让仓管填完数量点提交才被后端 409 拒。
  if (orderNotReceivable) {
    const items = order.items || [];
    const totalQuantity = items.reduce((sum, it) => sum + Number(it.quantity || 0), 0);
    const totalReceivedQty = items.reduce((sum, it) => sum + Number(it.receivedQuantity || 0), 0);
    const displayUnit = items.length === 1 ? (items[0]?.unit || '') : '';
    const title = orderClosedOut
      ? (order.status === 'CANCELLED' ? '此单已取消' : '此单已提前关闭')
      : '此单已收满';
    const message = orderClosedOut
      ? `「${order.orderNumber}」已${order.status === 'CANCELLED' ? '取消' : '提前关闭'}，不再接收剩余数量。`
      : `这单已经收满（订单 ${totalQuantity}${displayUnit} / 已收 ${totalReceivedQty}${displayUnit}），不需要再收了。`;

    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content
            title="扫码入库录入"
            subtitle={order.orderNumber ? `订单 ${order.orderNumber}` : ''}
          />
        </Appbar.Header>
        <View style={styles.center} testID="wh-receipt-not-receivable">
          <Text style={styles.doneTitle}>{title}</Text>
          <Text style={styles.doneMessage}>{message}</Text>
          <View style={styles.doneButtonRow}>
            <Button mode="contained" onPress={() => navigation.goBack()}>
              返回
            </Button>
            <Button
              mode="outlined"
              onPress={() => navigation.navigate('WHPurchaseReceiveList')}
              style={styles.doneSecondaryBtn}
            >
              去待收货列表
            </Button>
          </View>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content
            title="扫码入库录入"
            subtitle={order.orderNumber ? `订单 ${order.orderNumber}` : ''}
          />
        </Appbar.Header>

        <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
          {/* 订单信息卡片 (只读, prefill) —— 2026-08-21 收敛: 只留他核对"这是不是这一车货"
              要用的三项 (订单号/供应商/期望交货), 下单日期与明细行数不帮他做这个判断,
              删掉两行 (防呆: 每屏只暴露当前步骤需要的信息)。 */}
          <Card style={styles.card}>
            <Card.Content>
              <Text style={styles.sectionTitle}>订单信息</Text>
              <View style={styles.kv}>
                <Text style={styles.kvLabel}>订单号</Text>
                <Text style={styles.kvValue}>{order.orderNumber}</Text>
              </View>
              <View style={styles.kv}>
                <Text style={styles.kvLabel}>供应商</Text>
                <Text style={styles.kvValue}>{order.supplierName || order.supplierId}</Text>
              </View>
              {order.expectedDeliveryDate && (
                <View style={styles.kv}>
                  <Text style={styles.kvLabel}>期望交货</Text>
                  <Text style={styles.kvValue}>{order.expectedDeliveryDate}</Text>
                </View>
              )}
            </Card.Content>
          </Card>

          {/* 收货明细 — 只要 2 字段必填 */}
          <Text style={styles.outerHeader}>收货明细 (仅录数量 + 日期)</Text>
          <Text style={styles.deBlameHint}>按实际到货填写即可，差异由采购处理</Text>
          {(order.items || []).map((it, idx) => {
            const abaca = isAbacaItem(it);
            const m = materialsById[it.materialTypeId];
            const row = rows[it.id] || {
              receivedQuantity: '',
              productionDate: todayStr(),
              materialPackagingSpecId: '',
              unit: unitFor(it),
              baseUnit: it.inventoryBaseUnitSnapshot || it.unit,
              factor: Number(it.packageToBaseFactorSnapshot || 1),
            };
            const packagingOptions = (packagingByMaterial[it.materialTypeId]?.packagingSpecs || [])
              .filter((spec) => spec.active !== false);
            const basePreview = Number(row.receivedQuantity || 0) * Number(row.factor || 1);
            // 防呆 Rule 1: 这一行单独已经收满了(订单整体还没收满, 只是这行没了) ——
            // 禁用输入 + 说明原因, 不要留一个空白框让人填完再被 handleSubmit 的越界校验拒。
            const rowRemaining = Math.max(0, Number(it.quantity || 0) - Number(it.receivedQuantity || 0));
            const rowFullyReceived = rowRemaining <= 1e-9;
            return (
              <Card key={it.id} style={styles.itemCard}>
                <Card.Content>
                  <View style={styles.itemHeaderRow}>
                    <Text style={styles.itemIndex}>第 {idx + 1} 行</Text>
                    {abaca && (
                      <Chip
                        compact
                        style={styles.abacaChip}
                        textStyle={styles.abacaChipText}
                      >
                        🥩 抄码品
                      </Chip>
                    )}
                  </View>

                  <View style={styles.kv}>
                    <Text style={styles.kvLabel}>原料</Text>
                    <Text style={styles.kvValue}>
                      {it.materialName || m?.name || it.materialTypeId}
                    </Text>
                  </View>
                  <View style={styles.kv}>
                    <Text style={styles.kvLabel}>订单数量</Text>
                    <Text style={styles.kvValue}>
                      {it.quantity} {it.unit}
                      {it.receivedQuantity > 0 ? `  (已收 ${it.receivedQuantity})` : ''}
                    </Text>
                  </View>

                  {abaca && (
                    <View style={styles.abacaBanner}>
                      <Text style={styles.abacaBannerText}>
                        本品按实际称重 {m?.abacaUnitPerBox ? `(${m.abacaUnitPerBox})` : ''} —
                        提交后自动写入称重日志 (boxIndex 自动分配, 可在抄码品详情页继续 append)
                      </Text>
                    </View>
                  )}

                  {!abaca && packagingOptions.length > 0 && (
                    <Menu
                      visible={packagingMenuItemId === it.id}
                      onDismiss={() => setPackagingMenuItemId(null)}
                      anchor={
                        <Button
                          mode="outlined"
                          icon="package-variant"
                          onPress={() => setPackagingMenuItemId(it.id)}
                          style={styles.packagingButton}
                          disabled={rowFullyReceived}
                        >
                          {`到货包装：1${row.unit}=${row.factor}${row.baseUnit}`}
                        </Button>
                      }
                    >
                      {packagingOptions.map((spec) => (
                        <Menu.Item
                          key={spec.id}
                          title={`${spec.name} · 1${spec.packageUnit}=${spec.conversionFactor}${spec.baseUnit}`}
                          onPress={() => {
                            const remainingBase = Math.max(
                              0,
                              Number(it.quantity || 0) - Number(it.receivedQuantity || 0),
                            ) * Number(it.packageToBaseFactorSnapshot || 1);
                            updateRow(it.id, {
                              materialPackagingSpecId: spec.id,
                              unit: spec.packageUnit,
                              baseUnit: spec.baseUnit,
                              factor: Number(spec.conversionFactor),
                              receivedQuantity: String(Number((remainingBase / Number(spec.conversionFactor)).toFixed(4))),
                            });
                            setPackagingMenuItemId(null);
                          }}
                        />
                      ))}
                    </Menu>
                  )}

                  {rowFullyReceived ? (
                    <Text style={styles.rowDoneHint} testID={`wh-receipt-row-done-${it.id}`}>
                      本行已收满（{it.quantity} {it.unit}），无需再收
                    </Text>
                  ) : (
                    <View style={styles.fieldsRow}>
                      <TextInput
                        label={abaca ? `估算重量 (${unitFor(it)}) *` : `数量 (${unitFor(it)}) *`}
                        value={row.receivedQuantity}
                        onChangeText={(t) =>
                          updateRow(it.id, { receivedQuantity: t.replace(/[^0-9.]/g, '') })
                        }
                        mode="outlined"
                        keyboardType="numeric"
                        style={[styles.field, styles.flex1]}
                        contentStyle={{ fontSize: 24 }}
                        placeholder={abaca ? '入库以实际称重为准' : ''}
                        disabled={submitting}
                      />
                      <TextInput
                        label="商品日期 *"
                        value={row.productionDate}
                        onChangeText={(t) => updateRow(it.id, { productionDate: t })}
                        mode="outlined"
                        placeholder="YYYY-MM-DD"
                        style={[styles.field, styles.flex1]}
                        disabled={submitting}
                      />
                    </View>
                  )}
                  {!abaca && !rowFullyReceived && (
                    <Text style={styles.baseQuantityPreview}>
                      本次入库折合：{basePreview} {row.baseUnit}
                    </Text>
                  )}
                </Card.Content>
              </Card>
            );
          })}

          {/* SP4: 可选录入厂号/产地 — 填写后提交时写入每个批次。
              2026-08-21: 默认收起, 只留一行可点的入口 —— 这不是"仅录数量+日期"
              这个核心任务要用的东西, 常驻展开只会让他在真正要填的两个字段前多滑一屏。 */}
          <Card style={styles.card}>
            <Card.Content>
              <TouchableOpacity
                style={styles.traceToggleRow}
                onPress={() => setTraceInfoExpanded((v) => !v)}
                accessibilityLabel="展开或收起厂号产地"
              >
                <Text style={styles.sectionTitle}>
                  厂号 / 产地（可选，追溯用）
                </Text>
                <Text style={styles.traceToggleIcon}>
                  {traceInfoExpanded ? '收起 ▲' : '展开 ▼'}
                </Text>
              </TouchableOpacity>
              {traceInfoExpanded && (
                <>
                  <Text style={styles.helperText}>
                    如需追溯来源，可在此录入。留空则跳过。
                  </Text>
                  <TextInput
                    label="厂号"
                    value={factoryNumber}
                    onChangeText={setFactoryNumber}
                    mode="outlined"
                    style={styles.field}
                    placeholder="如: S-2024-008"
                    disabled={submitting}
                  />
                  <TextInput
                    label="产地"
                    value={originPlace}
                    onChangeText={setOriginPlace}
                    mode="outlined"
                    style={[styles.field, { marginTop: 8 }]}
                    placeholder="如: 山东章丘"
                    disabled={submitting}
                  />
                </>
              )}
            </Card.Content>
          </Card>

          {/* Issue #794: 收货拍照附件 — 必填; 建完草稿后、确认入库前上传 */}
          <Card style={styles.card}>
            <Card.Content>
              <Text style={styles.sectionTitle}>
                收货照片 ({photos.length})
              </Text>
              <Text style={styles.helperText}>
                拍照留单, 提交后随入库单一起保存。可点缩略图删除。
              </Text>

              {photos.length > 0 && (
                <View style={styles.photoGrid}>
                  {photos.map((p, idx) => (
                    <View key={`${p.uri}-${idx}`} style={styles.photoThumbWrap}>
                      <Image source={{ uri: p.uri }} style={styles.photoThumb} />
                      <TouchableOpacity
                        style={styles.photoDeleteBtn}
                        onPress={() => removePhoto(idx)}
                        accessibilityLabel="删除此照片"
                        hitSlop={{ top: 12, bottom: 12, left: 12, right: 12 }}
                      >
                        <Text style={styles.photoDeleteIcon}>×</Text>
                      </TouchableOpacity>
                    </View>
                  ))}
                </View>
              )}

              <View style={styles.photoButtonRow}>
                <Button
                  mode="outlined"
                  icon="camera"
                  onPress={takePhoto}
                  disabled={submitting}
                  style={styles.photoButton}
                  compact
                >
                  拍照
                </Button>
                <Button
                  mode="outlined"
                  icon="image-multiple"
                  onPress={pickPhotoFromGallery}
                  disabled={submitting}
                  style={styles.photoButton}
                  compact
                >
                  从相册
                </Button>
              </View>
            </Card.Content>
          </Card>

          <View style={styles.bottomSpacer} />
        </ScrollView>

        <View style={styles.footer}>
          <Button
            mode="contained"
            onPress={handleSubmit}
            loading={submitting}
            disabled={submitting || totalReceivedRows === 0}
            icon="check"
          >
            提交入库 ({totalReceivedRows} 行)
          </Button>
          {/*
            提前关单：**只在短收时出现**（Steve：「如果少于预计数量，还需要一个额外的按钮」）。
            收满或超收时后端会自动关闭，这时候显示它只会让人误按。
          */}
          {isShortReceipt && (
            <Button
              mode="outlined"
              onPress={() => setCloseDialogVisible(true)}
              disabled={submitting || closing}
              icon="lock-check"
              style={styles.closeShortBtn}
              textColor="#e6a23c"
            >
              本次收不齐，提前关单
            </Button>
          )}
        </View>

        <Portal>
          <Dialog visible={closeDialogVisible} onDismiss={() => setCloseDialogVisible(false)}>
            <Dialog.Title>提前关单</Dialog.Title>
            <Dialog.Content>
              <Text style={{ marginBottom: 8, color: '#606266' }}>
                {`关闭后「${orderNumber}」不再接收剩余数量，已收部分不受影响。`}
              </Text>
              <RadioButton.Group
                onValueChange={(v) => setCloseReason(v as PurchaseReceivingCloseReason)}
                value={closeReason}
              >
                {CLOSE_REASONS.map((r) => (
                  <RadioButton.Item key={r.code} label={r.label} value={r.code} />
                ))}
              </RadioButton.Group>
              {closeReason === 'OTHER' && (
                <TextInput
                  mode="outlined"
                  label="补充说明（必填）"
                  value={closeNotes}
                  onChangeText={setCloseNotes}
                  multiline
                  numberOfLines={3}
                />
              )}
            </Dialog.Content>
            <Dialog.Actions>
              <Button onPress={() => setCloseDialogVisible(false)} disabled={closing}>取消</Button>
              <Button onPress={doCloseShort} loading={closing} disabled={closing}>确认关单</Button>
            </Dialog.Actions>
          </Dialog>
        </Portal>
      </SafeAreaView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  scroll: { flex: 1 },
  scrollContent: { padding: 12 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center', gap: 12, padding: 24 },
  loadingText: { fontSize: 14, color: '#666' },
  doneTitle: { fontSize: 18, fontWeight: '700', color: '#2d3748', textAlign: 'center' },
  doneMessage: { fontSize: 14, color: '#606266', textAlign: 'center', lineHeight: 22, marginTop: 4 },
  doneButtonRow: { flexDirection: 'row', gap: 12, marginTop: 16 },
  doneSecondaryBtn: { marginLeft: 4 },

  card: { marginBottom: 12, borderRadius: 8 },
  sectionTitle: { fontSize: 15, fontWeight: '600', marginBottom: 8, color: '#2d3748' },
  outerHeader: { fontSize: 15, fontWeight: '600', marginBottom: 8, marginTop: 4, color: '#2d3748' },
  traceToggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: 44,
  },
  traceToggleIcon: { fontSize: 13, color: '#4CAF50', fontWeight: '600' },

  itemCard: { marginBottom: 12, borderRadius: 8 },
  itemHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 6,
  },
  itemIndex: { fontSize: 13, color: '#666', fontWeight: '500' },
  abacaChip: { backgroundColor: '#fef3c7' },
  abacaChipText: { color: '#92400e', fontSize: 12 },

  kv: { flexDirection: 'row', paddingVertical: 4 },
  kvLabel: { width: 80, fontSize: 13, color: '#718096' },
  kvValue: { flex: 1, fontSize: 13, color: '#2d3748' },

  abacaBanner: {
    backgroundColor: '#fef3c7',
    borderLeftWidth: 3,
    borderLeftColor: '#f59e0b',
    paddingVertical: 6,
    paddingHorizontal: 10,
    marginTop: 8,
    marginBottom: 8,
    borderRadius: 4,
  },
  abacaBannerText: { fontSize: 12, color: '#92400e', lineHeight: 18 },

  fieldsRow: { flexDirection: 'row', gap: 8, marginTop: 6 },
  packagingButton: { marginTop: 8, marginBottom: 4 },
  baseQuantityPreview: { fontSize: 13, color: '#2563eb', marginTop: 4 },
  rowDoneHint: {
    fontSize: 13,
    color: '#67c23a',
    backgroundColor: '#f0f9eb',
    borderRadius: 4,
    paddingVertical: 8,
    paddingHorizontal: 10,
    marginTop: 6,
  },
  field: { backgroundColor: 'transparent' },
  flex1: { flex: 1 },

  helperText: { fontSize: 12, color: '#718096', lineHeight: 18, marginBottom: 8 },
  deBlameHint: { fontSize: 12, color: '#718096', lineHeight: 18, marginBottom: 10, fontStyle: 'italic' },

  // Issue #794: 拍照附件样式
  photoGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 8,
  },
  photoThumbWrap: {
    position: 'relative',
    width: 72,
    height: 72,
  },
  photoThumb: {
    width: 72,
    height: 72,
    borderRadius: 6,
    backgroundColor: '#f4f4f5',
  },
  photoDeleteBtn: {
    position: 'absolute',
    top: -6,
    right: -6,
    width: 22,
    height: 22,
    borderRadius: 11,
    backgroundColor: 'rgba(0,0,0,0.65)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  photoDeleteIcon: {
    color: '#fff',
    fontSize: 16,
    lineHeight: 18,
    fontWeight: '600',
  },
  photoButtonRow: {
    flexDirection: 'row',
    gap: 8,
  },
  photoButton: {
    flex: 1,
  },

  bottomSpacer: { height: 24 },

  closeShortBtn: { marginTop: 8, borderColor: '#e6a23c' },
  footer: {
    padding: 12,
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#e0e0e0',
  },
});
