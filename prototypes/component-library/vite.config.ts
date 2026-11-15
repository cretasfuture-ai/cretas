import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import ui from '@nuxt/ui/vite'

export default defineConfig({
  base: '/samples/',
  resolve: { alias: { '@': path.join(path.dirname(fileURLToPath(import.meta.url)), 'legacy-src') }, dedupe: ['vue','pinia','vue-router','element-plus'] },
  cacheDir: '.cache/vite',
  plugins: [{ name: 'cretas-element-css-charset', enforce: 'pre', transform(code, id) { if (id.replaceAll('\\', '/').endsWith('/element-plus/dist/index.css')) return code.replace(/@charset\s+"UTF-8";/g, '') } }, vue(), ui({
    colorMode: false,
    router: false,
    dts: false,
    icon: { clientBundle: { scan: true } },
    ui: { colors: { primary: 'blue', neutral: 'slate' } },
    experimental: { componentDetection: true }
  })],
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1600,
    rollupOptions: { output: { manualChunks: { charts: ['echarts'], canvas: ['@vue-flow/core'], 'business-ui': ['element-plus'] } } }
  }
})
