/**
 * ProcessSopEntry — 报工页头卡里的「查看 SOP（N）」入口 (docs/specs/sop-attachments.md)。
 *
 * 本道工序挂了 SOP 文件 (PDF / 图片) 才出现；点开展开文件列表，点一份用系统方式打开 (Linking.openURL 签名地址，
 * 不加原生依赖、可走 OTA)。没有 SOP → 整段不渲染，页面和以前一样。
 *
 * UX Flow Analysis 见 spec：F1 入口整行 ≥ 48 高 / F2 无 SOP 不渲染 / F3 加载失败写原因 + 重试 (不当成 0 个) /
 * F4 列表标题带工序名 / F6 打开失败 appAlert 讲清楚 / F7 哨兵与空工序不请求 / F9 请求中显示进度、换道丢弃旧结果。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Linking, StyleSheet, Text, View } from 'react-native';
import { TouchableRipple } from 'react-native-paper';
import { attachmentApi, SopAttachmentMetadata } from '../../services/api/attachmentApi';
import { appAlert } from '../ui/AppDialog';
import { describeSopError, formatSopSize, isRealWorkProcessId, sopKindLabel } from '../../utils/processSop';

interface Props {
  /** 当前任务的 workProcessId；哨兵任务 (`__MATERIAL_INPUT__` / `__FINAL_OUTPUT__`) 与空值一律不请求、不渲染。 */
  workProcessId?: string | null;
  processName?: string | null;
}

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; files: SopAttachmentMetadata[] };

export const ProcessSopEntry: React.FC<Props> = ({ workProcessId, processName }) => {
  const [state, setState] = useState<LoadState>({ status: 'idle' });
  const [expanded, setExpanded] = useState(false);
  const [openingId, setOpeningId] = useState<string | null>(null);
  const seq = useRef(0);

  const load = useCallback(async () => {
    const mySeq = ++seq.current;
    if (!isRealWorkProcessId(workProcessId)) {
      setState({ status: 'idle' });
      return;
    }
    setState({ status: 'loading' });
    try {
      const files = await attachmentApi.list('WORK_PROCESS', workProcessId);
      if (mySeq !== seq.current) return;   // 已换道，旧结果丢弃
      setState({ status: 'ready', files });
    } catch (e) {
      if (mySeq !== seq.current) return;
      setState({ status: 'error', message: describeSopError(e) });
    }
  }, [workProcessId]);

  useEffect(() => {
    setExpanded(false);
    void load();
    return () => {
      seq.current += 1;   // 卸载 / 换道后不再回写 state
    };
  }, [load]);

  const openFile = useCallback(async (file: SopAttachmentMetadata) => {
    if (openingId != null) return;
    setOpeningId(file.id);
    try {
      const url = await attachmentApi.getSignedDownloadUrl(file.id);
      await Linking.openURL(url);
    } catch (e) {
      appAlert(
        '打不开这份 SOP',
        `${describeSopError(e)}\n请检查网络后再点一次；仍打不开请联系生产主管。`,
      );
    } finally {
      setOpeningId(null);
    }
  }, [openingId]);

  if (!isRealWorkProcessId(workProcessId) || state.status === 'idle') return null;

  if (state.status === 'loading') {
    return (
      <View style={styles.statusRow} testID="process-sop-loading">
        <ActivityIndicator size="small" color="#E8732E" />
        <Text style={styles.statusText}>正在读取 SOP…</Text>
      </View>
    );
  }

  if (state.status === 'error') {
    return (
      <View style={styles.errorBox} testID="process-sop-error">
        <Text style={styles.errorText}>SOP 暂时加载失败：{state.message}</Text>
        <TouchableRipple style={styles.retry} onPress={load} accessibilityRole="button" testID="process-sop-retry">
          <Text style={styles.retryText}>重试</Text>
        </TouchableRipple>
      </View>
    );
  }

  const files = state.files;
  if (files.length === 0) return null;

  return (
    <View style={styles.wrap} testID="process-sop-entry">
      <TouchableRipple
        style={styles.entry}
        onPress={() => setExpanded((v) => !v)}
        accessibilityRole="button"
        accessibilityLabel={`查看 SOP，共 ${files.length} 份`}
        testID="process-sop-toggle"
      >
        <Text style={styles.entryText}>
          {expanded ? '收起 SOP' : `查看 SOP（${files.length}）`}
        </Text>
      </TouchableRipple>
      {expanded ? (
        <View style={styles.list} testID="process-sop-list">
          <Text style={styles.listTitle}>{processName ? `${processName} 的 SOP` : '本道工序的 SOP'}</Text>
          {files.map((file) => (
            <TouchableRipple
              key={file.id}
              style={styles.fileRow}
              onPress={() => openFile(file)}
              disabled={openingId != null}
              accessibilityRole="button"
              accessibilityLabel={`打开 ${file.fileName}`}
              testID={`process-sop-file-${file.id}`}
            >
              <View style={styles.fileInner}>
                <View style={styles.fileMeta}>
                  <Text style={styles.fileName} numberOfLines={2}>{file.fileName}</Text>
                  <Text style={styles.fileSub}>{sopKindLabel(file)} · {formatSopSize(file.fileSize)}</Text>
                </View>
                {openingId === file.id ? (
                  <ActivityIndicator size="small" color="#E8732E" />
                ) : (
                  <Text style={styles.openText}>打开</Text>
                )}
              </View>
            </TouchableRipple>
          ))}
        </View>
      ) : null}
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { marginTop: 8 },
  entry: {
    minHeight: 48,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#E8732E',
    backgroundColor: '#FFF4EC',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 12,
  },
  entryText: { fontSize: 16, fontWeight: '600', color: '#C2570F' },
  list: { marginTop: 8 },
  listTitle: { fontSize: 13, color: '#6B7280', marginBottom: 6 },
  fileRow: {
    minHeight: 56,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#E5E7EB',
    backgroundColor: '#FFFFFF',
    paddingHorizontal: 12,
    paddingVertical: 8,
    marginBottom: 8,
    justifyContent: 'center',
  },
  fileInner: { flexDirection: 'row', alignItems: 'center' },
  fileMeta: { flex: 1, paddingRight: 8 },
  fileName: { fontSize: 15, fontWeight: '600', color: '#111827' },
  fileSub: { fontSize: 12, color: '#6B7280', marginTop: 2 },
  openText: { fontSize: 15, fontWeight: '600', color: '#C2570F' },
  statusRow: { flexDirection: 'row', alignItems: 'center', marginTop: 8, minHeight: 32 },
  statusText: { marginLeft: 8, fontSize: 13, color: '#6B7280' },
  errorBox: {
    marginTop: 8,
    padding: 10,
    borderRadius: 8,
    backgroundColor: '#FEF2F2',
    borderWidth: 1,
    borderColor: '#FCA5A5',
  },
  errorText: { fontSize: 13, color: '#B91C1C' },
  retry: {
    minHeight: 44,
    alignSelf: 'flex-start',
    justifyContent: 'center',
    paddingHorizontal: 12,
    marginTop: 4,
  },
  retryText: { fontSize: 15, fontWeight: '600', color: '#B91C1C' },
});

export default ProcessSopEntry;
