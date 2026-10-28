/**
 * 建厂就绪（本厂只读）
 * 显示采购/生产/发货各自是否具备开展条件（含包材换算与岗位账号），
 * 全部满足后给出「去新建第一笔」入口，跳到现有业务列表页。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAHomeStackParamList } from '../../../types/navigation';
import { getMyFactoryReadiness, ReadinessDomain, SelfReadiness } from '../../../services/api/factoryReadinessApiClient';
import { CHECK_STATE_LABEL, DOMAIN_STATE_LABEL, firstActionTarget, missingChecks } from './readinessView';

type Nav = NativeStackNavigationProp<FAHomeStackParamList, 'FactoryReadiness'>;

export function FactoryReadinessScreen() {
  const navigation = useNavigation<Nav>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [data, setData] = useState<SelfReadiness | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    setData(null);
    try {
      setData(await getMyFactoryReadiness());
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '建厂就绪状态读取失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const domains: ReadinessDomain[] =
    data?.state === 'READY_TO_READ' ? data.readiness.businessReadiness ?? [] : [];

  return (
    <SafeAreaView style={styles.container} edges={['bottom']} testID="factory-readiness-screen">
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.hint}>只读取本厂的日常业务准备情况，不会修改任何资料或单据。</Text>
        {loading && <ActivityIndicator testID="readiness-loading" />}
        {!!error && (
          <View style={styles.card} testID="readiness-error">
            <Text style={styles.bad}>{error}</Text>
            <TouchableOpacity onPress={load} testID="readiness-retry">
              <Text style={styles.link}>重试</Text>
            </TouchableOpacity>
          </View>
        )}
        {data?.state === 'NO_CANONICAL_BLUEPRINT' && (
          <View style={styles.card} testID="readiness-no-blueprint">
            <Text style={styles.bad}>缺正式资料：本厂还没有正式建厂蓝图</Text>
          </View>
        )}
        {data?.state === 'READY_TO_READ' && domains.length === 0 && (
          <View style={styles.card} testID="readiness-no-domains">
            <Text style={styles.bad}>无法判断：报告没有给出业务就绪情况</Text>
          </View>
        )}
        {domains.map((domain) => {
          const target = firstActionTarget(domain);
          const gaps = domain.applicability === 'APPLICABLE' ? missingChecks(domain) : [];
          return (
            <View key={domain.domain} style={styles.card} testID={`readiness-domain-${domain.domain}`}>
              <Text style={styles.title}>
                {domain.label}：{DOMAIN_STATE_LABEL[domain.state]}
              </Text>
              <Text style={styles.sub}>{domain.applicabilityBasis}</Text>
              {gaps.map((check) => (
                <Text key={check.code} style={styles.bad}>
                  {check.label}：{CHECK_STATE_LABEL[check.state]}（{check.detail}）→ 请在「{check.remedyLabel}」补齐
                </Text>
              ))}
              {target && (
                <TouchableOpacity
                  style={styles.button}
                  testID={`readiness-first-action-${domain.domain}`}
                  onPress={() => navigation.navigate(target.screen)}
                >
                  <Text style={styles.buttonText}>{target.label}</Text>
                </TouchableOpacity>
              )}
            </View>
          );
        })}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  content: { padding: 16, gap: 12 },
  hint: { color: '#606266', fontSize: 13 },
  card: { backgroundColor: '#fff', borderRadius: 8, padding: 12, gap: 6 },
  title: { fontSize: 16, fontWeight: '600', color: '#303133' },
  sub: { fontSize: 12, color: '#909399' },
  bad: { fontSize: 13, color: '#d9534f' },
  link: { color: '#2196F3', fontSize: 14 },
  button: { backgroundColor: '#2196F3', borderRadius: 6, paddingVertical: 10, alignItems: 'center', marginTop: 6 },
  buttonText: { color: '#fff', fontSize: 15 },
});

export default FactoryReadinessScreen;
