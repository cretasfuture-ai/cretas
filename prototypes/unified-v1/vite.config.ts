// 六膳门工作区（统一外壳内的同源 iframe）构建配置。依赖取自 samples-v5/node_modules（与味美、六膳门同一套），不建 junction。
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import ui from '@nuxt/ui/vite'
const here=path.dirname(fileURLToPath(import.meta.url))
export default defineConfig({
  root:here,base:'/__unified/lsm/',publicDir:false,
  cacheDir:path.resolve(here,'../../.cache/vite-unified-lsm'),
  plugins:[vue(),ui({colorMode:false,router:false,dts:false,icon:{clientBundle:{scan:true}},ui:{colors:{primary:'blue',neutral:'slate'}}})],
  resolve:{dedupe:['vue']},
  build:{outDir:path.resolve(here,'dist'),emptyOutDir:true,sourcemap:false}
})
