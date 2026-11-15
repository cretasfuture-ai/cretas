'use strict';
const fs=require('node:fs'),path=require('node:path'),root=path.dirname(__dirname),list=JSON.parse(fs.readFileSync(path.join(__dirname,'home-critical-assets.json'))).urls;
for(const url of list){if(!/^\/assets\/[A-Za-z0-9_.-]+\.js$/.test(url)||!fs.existsSync(path.join(root,'public/native',url)))throw Error('Invalid measured home dependency: '+url)}
// modulepreload fetches and compiles; only normal imports execute the existing components.
const catalog=JSON.parse(fs.readFileSync(path.join(root,'private/observed-catalog.json'))),routes=new Set(['/dashboard',...catalog.map(r=>r.path.split('?')[0])]);
const deferred=new Set(['pdf-lib-DIyK-pLh.js','echarts-VthzOFJC.js','echarts-BAv7SgNl.js','auth-BBsZaxor.js','index-Do-kwv8k.js','DashboardDefault-DlL0udQC.js']);
const common=list.filter(url=>!deferred.has(path.basename(url)));
const pageAssets={'/dashboard':['index-Do-kwv8k.js','DashboardDefault-DlL0udQC.js'],'/procurement/ai-purchase':['index-DRTnVvLv.js','AiOperationCard-CtmcY6FZ.js','intent-chat-3442znGV.js'],'/procurement/orders':['list-x__r5ydI.js']};
// Fetch small shell dependencies concurrently. Authorization still runs in the
// original router guard; page components execute only when normally imported.
exports.links=pathname=>routes.has(pathname)?[...new Set(['/startup-preload.js','/startup-auth.js',...common,...(pageAssets[pathname]||[]).map(f=>'/assets/'+f)])].map(url=>'<link rel="modulepreload" crossorigin href="'+url+'">').join(''):'';
