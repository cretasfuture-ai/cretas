import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
const read=(path:string)=>readFileSync(resolve(__dirname,'../../../..',path),'utf8')
describe('native presentation has no substitute business interface',()=>{
 it('keeps the account role route fence',()=>{expect(read('src/router/index.ts')).toContain("path: 'demo-flow'");expect(read('src/router/index.ts')).toContain("roles: ['factory_super_admin']")})
 it('keeps the real business router view and only a top control strip',()=>{
  const layout=read('src/components/layout/AppLayout.vue')
  expect(layout).toContain('<F006NativeToolbar');expect(layout).toContain(':component="Component"')
  expect(layout).not.toContain('DemoFlowConsole');expect(layout).not.toContain('has-demo-guide')
  expect(read('src/components/layout/F006NativeToolbar.vue')).not.toMatch(/<(?:el-form|el-input|el-table|aside)\b/)
 })
 it('cannot invoke the old direct-writing flow engine as native UI automation',()=>{
  for(const path of ['src/demo/f006NativeRuntime.ts','src/demo/nativeCommerceSteps.ts','src/demo/nativeProductionSteps.ts','src/views/demo-flow/index.vue'])expect(read(path)).not.toContain('executeStep')
 })
})
