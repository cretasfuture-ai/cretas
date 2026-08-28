import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    server: {
      port: 5173,
      host: '127.0.0.1',  // Bind to IPv4 to fix connection issues on Windows
      proxy: {
        // 必须排在 '/api' 前面 —— 生产 nginx 用
        // snippets/smart-bi-routing.conf 把这几条子路径分流给 Python(8083),
        // 而 dev 这边原本把 /api/* 全给了 Java, 于是本地/测试环境 404 而生产
        // 正常。2026-08-28 F006 全路由验收因此误判过一轮「生产缺陷」。
        //
        // ⛔ 不要放宽成 smart-bi/analysis/ 通配: analysis/dynamic 与
        //    analysis/dynamic/kpis 由 **Java** 提供(SmartBIDashboardController)。
        // ⚠️ 生产那条规则还带租户 allow-list; dev 不复现它 —— Python 服务端
        //    自己强制跨租户隔离(越权返回 403), allow-list 不是安全边界。
        '^/api/mobile/[A-Za-z0-9_-]+/smart-bi/(analysis/(finance|sales|kitchen-cost|department|region|inventory|procurement)|mapping-review)': {
          target: env.VITE_PYTHON_URL || 'http://localhost:8083',
          changeOrigin: true,
        },
        '/api': {
          target: env.VITE_BACKEND_URL || 'http://localhost:10010',
          changeOrigin: true,
        },
        // Python SmartBI 服务代理 - 解决跨域问题
        // /smartbi-api/api/chart/build → http://localhost:8083/api/chart/build
        '/smartbi-api': {
          target: env.VITE_PYTHON_URL || 'http://localhost:8083',
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/smartbi-api/, ''),
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      chunkSizeWarningLimit: 1300,
      // Only preload chunks that the entry synchronously needs — skip transitive lazy chunks
      // (otherwise Vite preloads echarts/pdf-lib on every page load even though they're lazy)
      modulePreload: {
        resolveDependencies: (_filename, deps) => {
          return deps.filter(
            (dep) =>
              !dep.includes('echarts-') &&
              !dep.includes('pdf-lib-') &&
              !dep.includes('xlsx-lib-') &&
              !dep.includes('vue-flow-') &&
              // Round 6: el-icons 也 skip preload — 登录页仅需极少 icon, runtime lazy
              !dep.includes('el-icons-'),
          );
        },
      },
      rollupOptions: {
        output: {
          // 注意: 不要拆 element-plus / vue / pinia / vue-router (会 TDZ)
          // UX Round 6: 只拆独立重包 + icons (纯 SVG, 无循环依赖)
          manualChunks: {
            'echarts': ['echarts', 'vue-echarts'],
            'xlsx-lib': ['xlsx'],
            'pdf-lib': ['jspdf', 'html2canvas'],
            'vue-flow': [
              '@vue-flow/core',
              '@vue-flow/controls',
            ],
            // Round 6: icons 独立 chunk (~50KB, 纯数据, 登录页仅用少数可 tree-shake)
            'el-icons': ['@element-plus/icons-vue'],
          },
        },
      },
    },
  };
});
