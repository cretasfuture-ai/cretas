/**
 * AttachmentList — 列出某实体的全部附件 (缩略图 + 文件名 + 上传时间).
 *
 * 用法:
 *   <AttachmentList entityType="PURCHASE_ORDER" entityId={order.id} refreshKey={x} />
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15 (C-ATT-1 Day 4)
 */

import React, { useEffect, useState } from 'react';
import { View, StyleSheet, Image, FlatList, TouchableOpacity, Linking, Modal, Dimensions } from 'react-native';
import { Text, ActivityIndicator, Card, IconButton } from 'react-native-paper';
import { attachmentApi, Attachment, AttachmentEntityType } from '../../services/api/attachmentApi';

/**
 * 图片类附件在**应用内**看, 不交给系统浏览器。
 *
 * 客户 2026-08-12:「[质检] 无法查看图片 —— 质检记录里的图片打不开」。
 *
 * 排查实测(prod, 2026-08-19):
 *   · attachments 表 QUALITY_CHECK 共 4314 张, 全部有 thumbnail_url
 *   · 匿名 GET 原图  -> HTTP 200, 2.4MB, 真 JPEG (3072x4096)
 *   · 匿名 GET 缩略图 -> HTTP 200, ~8KB
 *   ⇒ URL 和 OSS 都是好的(cretas-media 是 public-read: 取不存在的对象返回
 *     NoSuchKey 而不是 AccessDenied), **不是签名问题**。
 *
 * 坏的是「打开」这一步: 原来点缩略图走 `Linking.openURL(fileUrl)`, 把用户丢给
 * 系统浏览器 —— 应用外跳、可能被拦、可能变成下载、也可能压根没有可处理的 App,
 * 任何一种在用户那里都叫「图片打不开」。而这些是给仓管/质检员用的屏幕
 * (fool-proof-design: 别让他们离开当前流程)。
 *
 * ⇒ 图片走应用内全屏预览; 非图片(PDF 等)仍交给系统 App, 那才是对的分工。
 */
function isViewableImage(a: Attachment): boolean {
  return a.fileCategory === 'PHOTO' || !!a.thumbnailUrl;
}

interface Props {
  entityType: AttachmentEntityType;
  entityId: string;
  factoryId?: string;
  /** 当父组件需触发刷新, 改这个值. */
  refreshKey?: number;
  onDelete?: (att: Attachment) => void;
  emptyText?: string;
}

export function AttachmentList({ entityType, entityId, factoryId, refreshKey, onDelete, emptyText }: Props) {
  const [list, setList] = useState<Attachment[]>([]);
  const [loading, setLoading] = useState(false);
  /** 列表**加载**失败 —— 没有列表可显示, 才允许整块替换成错误文案。 */
  const [loadError, setLoadError] = useState<string | null>(null);
  /**
   * 单次**操作**失败(打开/删除)。
   * ⛔ 不能再塞进 loadError: 原来打开失败会把整个附件列表替换成一行红字,
   * 而且 error 只在 entityId/refreshKey 变化时才清 —— 点一次打不开的图,
   * 缩略图就**整屏消失且回不来**, 比「打不开」更像「图没了」。
   */
  const [actionError, setActionError] = useState<string | null>(null);
  /** 应用内全屏预览的那张图; null = 不展示。 */
  const [preview, setPreview] = useState<Attachment | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewFailed, setPreviewFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    attachmentApi
      .list(entityType, entityId, factoryId)
      .then((data) => {
        if (!cancelled) setList(data);
      })
      .catch((e) => {
        if (!cancelled) setLoadError(e?.message ?? '加载附件失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [entityType, entityId, factoryId, refreshKey]);

  const open = (a: Attachment) => {
    setActionError(null);
    if (isViewableImage(a)) {
      // 应用内看图 —— 不跳出去。原图平均 4.6MB, 所以要有明确的加载态,
      // 否则用户看到的是一块空白, 又是一次「打不开」。
      setPreviewLoading(true);
      setPreviewFailed(false);
      setPreview(a);
      return;
    }
    // 非图片(PDF/文档)交给系统 App —— 那是它该做的事。
    Linking.openURL(a.fileUrl).catch(() => {
      setActionError(`无法打开: ${a.fileName}`);
    });
  };

  const closePreview = () => {
    setPreview(null);
    setPreviewLoading(false);
    setPreviewFailed(false);
  };

  const handleDelete = async (a: Attachment) => {
    try {
      await attachmentApi.delete(a.id, factoryId);
      setList((prev) => prev.filter((x) => x.id !== a.id));
      onDelete?.(a);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '删除失败';
      setActionError(msg);
    }
  };

  if (loading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="small" />
      </View>
    );
  }

  // 只有「列表加载失败」才整块换成错误 —— 操作失败走上方横幅, 列表留着。
  if (loadError) {
    return <Text style={styles.error}>{loadError}</Text>;
  }

  if (list.length === 0) {
    return <Text style={styles.empty}>{emptyText ?? '暂无附件'}</Text>;
  }

  return (
    <View>
      {actionError ? (
        <TouchableOpacity onPress={() => setActionError(null)} accessibilityLabel="关闭错误提示">
          <Text style={styles.error}>{actionError}（点此关闭）</Text>
        </TouchableOpacity>
      ) : null}

      <Modal
        visible={preview !== null}
        transparent={false}
        animationType="fade"
        onRequestClose={closePreview}
      >
        <View style={styles.previewBackdrop}>
          <View style={styles.previewHeader}>
            <Text numberOfLines={1} style={styles.previewTitle}>
              {preview?.fileName ?? ''}
            </Text>
            <IconButton
              icon="close"
              iconColor="#fff"
              onPress={closePreview}
              accessibilityLabel="关闭图片"
            />
          </View>

          {preview ? (
            <Image
              testID="attachment-preview-image"
              source={{ uri: preview.fileUrl }}
              style={styles.previewImage}
              resizeMode="contain"
              onLoadStart={() => setPreviewLoading(true)}
              onLoadEnd={() => setPreviewLoading(false)}
              onError={() => {
                setPreviewLoading(false);
                setPreviewFailed(true);
              }}
            />
          ) : null}

          {previewLoading && !previewFailed ? (
            <View style={styles.previewOverlay} pointerEvents="none">
              <ActivityIndicator size="large" />
              <Text style={styles.previewHint}>原图较大，正在加载…</Text>
            </View>
          ) : null}

          {previewFailed ? (
            <View style={styles.previewOverlay}>
              <Text style={styles.previewHint}>图片加载失败，请检查网络后重试</Text>
              <TouchableOpacity
                onPress={() => {
                  // 交给系统 App 作为退路 —— 应用内看不了时至少还有一条路。
                  if (preview) {
                    Linking.openURL(preview.fileUrl).catch(() => {
                      setActionError(`无法打开: ${preview.fileName}`);
                    });
                  }
                }}
                accessibilityLabel="用浏览器打开"
              >
                <Text style={styles.previewFallback}>用浏览器打开</Text>
              </TouchableOpacity>
            </View>
          ) : null}
        </View>
      </Modal>

      <FlatList
      data={list}
      keyExtractor={(item) => item.id}
      scrollEnabled={false}
      renderItem={({ item }) => (
        <Card style={styles.card}>
          <View style={styles.row}>
            <TouchableOpacity
              testID={`attachment-open-${item.id}`}
              accessibilityLabel={`查看 ${item.fileName}`}
              onPress={() => open(item)}
              style={styles.thumbBox}
            >
              {item.thumbnailUrl || item.fileCategory === 'PHOTO' ? (
                <Image
                  source={{ uri: item.thumbnailUrl ?? item.fileUrl }}
                  style={styles.thumb}
                  resizeMode="cover"
                />
              ) : (
                <View style={[styles.thumb, styles.fileIcon]}>
                  <Text style={styles.fileIconText}>{getCategoryIcon(item.fileCategory)}</Text>
                </View>
              )}
            </TouchableOpacity>
            <View style={styles.meta}>
              <Text numberOfLines={1} style={styles.fileName}>
                {item.fileName}
              </Text>
              <Text style={styles.subText}>
                {formatSize(item.fileSize)} · {formatTime(item.uploadedAt)}
              </Text>
              {item.description ? (
                <Text numberOfLines={2} style={styles.subText}>
                  {item.description}
                </Text>
              ) : null}
            </View>
            <IconButton icon="delete-outline" onPress={() => handleDelete(item)} accessibilityLabel="删除附件" />
          </View>
        </Card>
      )}
      />
    </View>
  );
}

function getCategoryIcon(c: Attachment['fileCategory']): string {
  switch (c) {
    case 'VIDEO':
      return '🎬';
    case 'DOCUMENT':
      return '📄';
    case 'VOUCHER':
      return '🧾';
    case 'SIGNATURE':
      return '✍️';
    default:
      return '📎';
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso);
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  } catch {
    return iso;
  }
}

const styles = StyleSheet.create({
  card: { marginVertical: 4, padding: 8 },
  row: { flexDirection: 'row', alignItems: 'center' },
  thumbBox: { marginRight: 12 },
  thumb: { width: 56, height: 56, borderRadius: 6, backgroundColor: '#e8e8e8' },
  fileIcon: { justifyContent: 'center', alignItems: 'center' },
  fileIconText: { fontSize: 24 },
  meta: { flex: 1 },
  fileName: { fontWeight: '600', fontSize: 14 },
  subText: { fontSize: 12, color: '#666', marginTop: 2 },
  center: { padding: 16, alignItems: 'center' },
  empty: { padding: 16, color: '#999', textAlign: 'center' },
  error: { padding: 16, color: '#d32f2f', textAlign: 'center' },
  previewBackdrop: { flex: 1, backgroundColor: '#000' },
  previewHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingLeft: 16,
  },
  previewTitle: { flex: 1, color: '#fff', fontSize: 14 },
  previewImage: {
    width: Dimensions.get('window').width,
    flex: 1,
  },
  previewOverlay: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
  },
  previewHint: { color: '#fff', marginTop: 12, fontSize: 14 },
  previewFallback: {
    color: '#4da3ff',
    marginTop: 16,
    fontSize: 15,
    textDecorationLine: 'underline',
  },
});
