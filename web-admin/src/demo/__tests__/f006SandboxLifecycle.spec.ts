import { expect, it } from 'vitest'
import { serializeSandboxLifecycle } from '../f006SandboxLifecycle'
it('serializes late recovery, creation, refresh and END so stale READY cannot overwrite END',async()=>{
 let release:()=>void=()=>{};let status='EMPTY'
 const ready=new Promise<void>(resolve=>{release=resolve})
 const recovery=serializeSandboxLifecycle(async()=>{await ready;status='EMPTY'})
 const create=serializeSandboxLifecycle(async()=>{status='READY'})
 const refresh=serializeSandboxLifecycle(async()=>{status='READY'})
 const end=serializeSandboxLifecycle(async()=>{status='ENDED'})
 release();await Promise.all([recovery,create,refresh,end])
 expect(status).toBe('ENDED')
})
