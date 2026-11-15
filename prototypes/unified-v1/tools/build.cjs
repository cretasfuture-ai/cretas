'use strict';
// 构建六膳门工作区（iframe 内容）到 unified-v1/dist。依赖用 samples-v5/node_modules，不建 junction。
const {spawnSync}=require('child_process'),path=require('path');
const root=path.resolve(__dirname,'..'),parent=path.resolve(root,'../..');
const r=spawnSync(process.execPath,[path.join(parent,'node_modules/vite/bin/vite.js'),'build','--config',path.join(root,'vite.config.ts')],{cwd:parent,stdio:'inherit'});
if(r.status!==0){console.error('BUILD_FAILED rc='+r.status);process.exit(r.status||1)}
console.log('BUILD_OK');
