// G 骨架单独预览：node <vite> --config accounts/unified-v1/src/frame-g/preview/vite.preview.config.ts（端口 19069，只读代理 19063）
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
const here = path.dirname(fileURLToPath(import.meta.url))
const target = 'http://127.0.0.1:19063'
const px = { target, changeOrigin: true, headers: { host: '127.0.0.1:19063' } }
export default defineConfig({
  root: here, publicDir: false, cacheDir: path.resolve(here, '../../../../../.cache/vite-frame-g'),
  plugins: [vue()],
  server: { port: 19069, strictPort: true, host: '127.0.0.1', fs: { allow: [path.resolve(here, '../../../../..')] },
    proxy: { '/design-system.js': px, '/design-tokens.css': px, '/__unified/menu.json': px, '/system-kit/menu-map.json': px } }
})
