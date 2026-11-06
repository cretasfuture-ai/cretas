import {it,expect,vi} from 'vitest';
import {pythonFetch} from '../common';
import {getLaborSupplements,saveLaborSupplements} from '../laborSupplement';
vi.mock('../common',()=>({pythonFetch:vi.fn()}));
it('unwraps the actual Python envelope and preserves source keys for summary and saved receipts',async()=>{
  const value={sourceRunId:'live:abc',tasks:[],summary:{complete:false}};
  vi.mocked(pythonFetch).mockResolvedValueOnce({success:true,data:value,message:'ok'}).mockResolvedValueOnce({success:true,data:{saved:true,savedRevision:'1',savedCount:1,readbackPending:true},message:'saved'});
  expect(await getLaborSupplements([1])).toEqual(value);
  expect(pythonFetch).toHaveBeenCalledWith('/api/smartbi/blacklake/labor-supplements?project_ids=1',{preserveResponseKeys:true});
  expect(await saveLaborSupplements('live:abc',[])).toMatchObject({saved:true,readbackPending:true});
});
it('does not treat an HTTP200 business failure as usable supplemental data',async()=>{
  vi.mocked(pythonFetch).mockResolvedValueOnce({success:false,data:null,message:'范围无效'});
  await expect(getLaborSupplements([999])).rejects.toThrow('范围无效');
});
