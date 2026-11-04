import {describe,it,expect} from 'vitest';
import {allowedLocalPreview} from '../../tooling/blacklakeLocalPreview';
const valid=()=>({method:'POST',url:'/__blacklake/local-session',socket:{remoteAddress:'127.0.0.1'},
  headers:{host:'127.0.0.1:5178',origin:'http://127.0.0.1:5178','x-cretas-local-preview':'1','sec-fetch-site':'same-origin'}});
describe('local preview session boundary',()=>{
  it('accepts only the same-origin loopback preview request',()=>{expect(allowedLocalPreview(valid())).toBe(true);});
  it.each([
    {method:'GET'}, {url:'/__blacklake/local-session?factory=other'},
    {socket:{remoteAddress:'192.168.1.20'}},
  ])('rejects altered request context %j',override=>{expect(allowedLocalPreview({...valid(),...override})).toBe(false);});
  it.each([
    {origin:'https://untrusted.example'}, {origin:''}, {host:'0.0.0.0:5178'},
    {'x-cretas-local-preview':''},{'sec-fetch-site':'cross-site'},
  ])('rejects altered origin/host/header %j',headers=>{expect(allowedLocalPreview({...valid(),headers:{...valid().headers,...headers}})).toBe(false);});
});
