import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';
import { StorageService } from '../storage/storageService';
import { API_BASE_URL } from '../../constants/config';
import { useAuthStore } from '../../store/authStore';
import { logger } from '../../utils/logger';
import { currentActionId } from '../activityTracker';

// 创建ApiClient专用logger
const apiLogger = logger.createContextLogger('ApiClient');

// The response interceptor below returns response.data. Keep that runtime
// invariant at one boundary instead of repeating assertions in every method.
interface UnwrappedAxiosInstance extends AxiosInstance {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T>;
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>;
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>;
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T>;
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>;
}

class ApiClient {
  private client: UnwrappedAxiosInstance;

  constructor() {
    this.client = axios.create({
      baseURL: API_BASE_URL,
      timeout: 120000, // 2分钟超时，支持AI分析等长时间操作
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    }) as UnwrappedAxiosInstance;

    this.setupInterceptors();
  }

  private setupInterceptors(): void {
    // 请求拦截器 - 智能token管理
    this.client.interceptors.request.use(
      async (config) => {
        // 打印完整的请求URL
        const fullUrl = `${config.baseURL || ''}${config.url}`;
        apiLogger.debug(`API请求: ${config.method?.toUpperCase()} ${fullUrl}`, {
          params: config.params,
          data: config.data
        });

        // 只使用安全存储的访问token，不允许降级
        const accessToken = await StorageService.getSecureItem('secure_access_token');
        if (accessToken) {
          config.headers.Authorization = `Bearer ${accessToken}`;
          apiLogger.debug('使用SecureStore中的Token');
        } else {
          apiLogger.warn('SecureStore中未找到Token');
        }
        config.headers['X-Activity-Client'] = 'app';
        config.headers['X-Correlation-ID'] = config.headers['X-Correlation-ID'] ||
          (globalThis.crypto?.randomUUID?.() || `corr-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`);
        const clientActionId = currentActionId();
        if (clientActionId) config.headers['X-Client-Action-ID'] = clientActionId;
        return config;
      },
      (error) => Promise.reject(error)
    );

    // 响应拦截器 - 智能token刷新和错误处理
    this.client.interceptors.response.use(
      (response) => {
        // 统一解包response.data，所有API客户端直接使用数据
        // 这样避免了response.data || response的兼容性问题
        return response.data;
      },
      async (error) => {
        const originalRequest = error.config;

        if (error.response?.status === 401 && !originalRequest._retry) {
          originalRequest._retry = true;

          try {
            // 尝试使用refresh token刷新访问token (使用正确的key)
            const refreshToken = await StorageService.getSecureItem('secure_refresh_token');
            if (refreshToken) {
              const response = await this.refreshAccessToken(refreshToken);
              if (response.success && response.tokens) {
                // 保存新的tokens (使用正确的key)
                await StorageService.setSecureItem('secure_access_token', response.tokens.token || response.tokens.accessToken);
                await StorageService.setSecureItem('secure_refresh_token', response.tokens.refreshToken);

                // 重试原始请求
                originalRequest.headers.Authorization = `Bearer ${response.tokens.token || response.tokens.accessToken}`;
                return this.client(originalRequest);
              }
            }
          } catch (refreshError) {
            apiLogger.error('Token刷新失败', refreshError);
          }

          // 刷新失败，清除所有认证信息
          await this.clearAuthTokens();
          // 触发登出事件
          this.onAuthenticationFailed?.();
        }

        // 410 Gone — SmartBI Java→Python migration Phase 2A. Should be nginx-routed
        // to Python in normal flow; 410 only on dev / F999 / nginx misconfig regression.
        // Don't show Alert here — let consumer screens catch and decide UI.
        // Per chat 8 audit PR #214 §5.2 (2026-05-09).
        if (error.response?.status === 410) {
          const rawMsg = error.response?.data?.message as string | undefined;
          if (typeof rawMsg === 'string' && rawMsg.startsWith('SMARTBI_MIGRATED:')) {
            apiLogger.warn('[SMARTBI_MIGRATED] backend 410 — nginx routing gap?', {
              url: originalRequest?.url,
              message: rawMsg,
            });
          }
        }

        // 把后端的业务文案抬到 error.message 上。
        //
        // 测试反馈 2026-08-12「采购订单不能新建, 提示 request failed code 409」——
        // 那句 "Request failed with status code 409" 是 **axios 的默认文案**:
        // 拦截器原样 reject 原始 error, 而各屏统一读 `err.message` 显示, 于是后端
        // 精心给的 message(「该供应商未启用所选物料的供应关系」) 和 actionHint
        // (「请先在供应商详情的"供应原料"中启用该物料」) 全被丢掉, 用户只看到一个状态码。
        //
        // 防呆规范「4 位一体」第 b 条要求前端原样展示后端 message —— 这里是唯一能
        // 一次性接上的地方 (所有 RN 屏都经过这个拦截器)。
        // ⚠️ 只在后端真给了非空字符串时才覆盖; 网络层错误(超时/断网)保留 axios 原文案,
        //    否则会把「连不上」说成一句业务话术。
        const backendMessage = error.response?.data?.message;
        if (typeof backendMessage === 'string' && backendMessage.trim() !== '') {
          error.message = backendMessage;
          const hint = error.response?.data?.actionHint;
          if (typeof hint === 'string' && hint.trim() !== '') {
            error.actionHint = hint;
            error.message = `${backendMessage}\n\n${hint}`;
          }
        }

        return Promise.reject(error);
      }
    );
  }

  // Token刷新方法
  private async refreshAccessToken(refreshToken: string): Promise<any> {
    const response = await axios.post(`${API_BASE_URL}/api/mobile/auth/refresh`, {
      refreshToken
    });
    return response.data;
  }

  // 清除认证信息
  private async clearAuthTokens(): Promise<void> {
    await Promise.all([
      StorageService.removeSecureItem('secure_access_token'),
      StorageService.removeSecureItem('secure_refresh_token'),
      StorageService.removeSecureItem('secure_temp_token')
    ]);

    // 同步清除authStore状态，强制返回登录页
    try {
      useAuthStore.getState().logout();
      apiLogger.info('AuthStore已清除，用户将重定向到登录页');
    } catch (error) {
      apiLogger.error('清除AuthStore失败', error);
    }
  }

  // 认证失败回调
  public onAuthenticationFailed?: () => void;

  async get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    // 拦截器已统一返回response.data，直接返回即可
    return await this.client.get<T>(url, config);
  }

  async post<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    // 拦截器已统一返回response.data，直接返回即可
    return await this.client.post<T>(url, data, config);
  }

  async put<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    // 拦截器已统一返回response.data，直接返回即可
    return await this.client.put<T>(url, data, config);
  }

  async delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    // 拦截器已统一返回response.data，直接返回即可
    return await this.client.delete<T>(url, config);
  }

  async patch<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    // 拦截器已统一返回response.data，直接返回即可
    return await this.client.patch<T>(url, data, config);
  }
}

export const apiClient = new ApiClient();
