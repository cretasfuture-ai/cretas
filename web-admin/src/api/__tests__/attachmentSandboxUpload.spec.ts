import {afterEach,describe,expect,it,vi} from 'vitest'
const mocks=vi.hoisted(()=>({put:vi.fn().mockResolvedValue({success:true})}))
vi.mock('../request',()=>({default:{put:mocks.put}}))
import {sandboxSession} from '@/demo/f006Sandbox'
import {putFileWithProgress} from '../attachment'
afterEach(()=>{sandboxSession.value=null;mocks.put.mockClear();vi.unstubAllGlobals()})
const ready=()=>{sandboxSession.value={sessionId:'de174d1b-15d0-4b46-a64a-7049e140b58a',actor:1309,status:'READY'}}
describe('the original attachment control uploads into the owned sandbox',()=>{
 it('sends the real file through the authenticated scoped request client',async()=>{
  ready();const file=new File(['receipt'],'demo.png',{type:'image/png'});const progress=vi.fn()
  await putFileWithProgress('/F006/demo-sandbox-local-files/receipt_token',file,progress)
  expect(mocks.put).toHaveBeenCalledWith('/F006/demo-sandbox-local-files/receipt_token',file,expect.objectContaining({headers:{'Content-Type':'image/png'}}))
  expect(progress).toHaveBeenLastCalledWith(100)
 })
 it('refuses external, cross-factory, traversal and not-ready destinations before uploading',async()=>{
  ready()
  for(const url of ['https://example.com/file','/F007/demo-sandbox-local-files/file','/F006/demo-sandbox-local-files/../file'])await expect(putFileWithProgress(url,new File(['x'],'x'))).rejects.toThrow('当前沙盒')
  sandboxSession.value!.status='ENDING'
  await expect(putFileWithProgress('/F006/demo-sandbox-local-files/token',new File(['x'],'x'))).rejects.toThrow('隔离演示')
  expect(mocks.put).not.toHaveBeenCalled()
 })
 it('preserves the existing signed upload path outside a sandbox',async()=>{
  vi.stubGlobal('XMLHttpRequest',undefined);const fetch=vi.fn().mockResolvedValue({ok:true});vi.stubGlobal('fetch',fetch)
  const file=new File(['x'],'receipt.png',{type:'image/png'})
  await putFileWithProgress('https://example.com/signed-file',file)
  expect(fetch).toHaveBeenCalledWith('https://example.com/signed-file',expect.objectContaining({method:'PUT',body:file}))
  expect(mocks.put).not.toHaveBeenCalled()
 })
})
