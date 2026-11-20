'use strict';
// These four immutable account reads already use Workspace.read in the normal final dispatcher.
// Skip unrelated business handlers; retain the same scoped workspace, response and no-store policy.
exports.read=(w,method,target,identity)=>{
 if(method!=='GET'||identity.username!=='ydwm_admin'||identity.factoryId!=='FOOD_0000_001')return null;
 const base='/api/mobile/FOOD_0000_001',paths=new Set([base+'/permissions/current',base+'/config/disabled-modules',base+'/feature-config',base+'/canvas/user-module-access/'+identity.userId]);
 if(!paths.has(target))return null;
 const response=w.read(method,target,null);
 return response?.status===200&&response.body?.success!==false?response:null;
};
