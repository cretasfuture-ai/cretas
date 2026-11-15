'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),root=path.join(__dirname,'..');
const cache=new Map();
function current(which='candidate'){const f=path.join(root,'private/fast-ui-'+which+'.json');if(!fs.existsSync(f))return null;const text=fs.readFileSync(f,'utf8'),prior=cache.get(f);if(prior?.stamp===text)return prior.value;const selection=JSON.parse(text);if(!/^[a-f0-9]{64}$/.test(selection.buildId))throw Error('Invalid fast build selection');const dir=path.join(root,'public/fast-ui',selection.buildId),manifest=JSON.parse(fs.readFileSync(path.join(dir,'manifest.json'))),meta=JSON.parse(fs.readFileSync(path.join(dir,'meta.json')));if(manifest.buildId!==selection.buildId)throw Error('Fast build mismatch');const value={selection,manifest,meta,dir};cache.set(f,{stamp:text,value});return value;}
function enabled(url){if(url.searchParams.get('uiBuild')==='classic')return null;const which=url.searchParams.get('uiBuild')==='fast'?'candidate':'active',c=current(which);return c&&(which==='candidate'||c.selection.enabled===true)?c:null;}
const keyFor=(name,c)=>Object.keys(c.meta.outputs).find(k=>k.endsWith('/'+name));
function criticalPreloads(c){if(c.criticalPreloads)return c.criticalPreloads;const files=new Set();function add(file){if(!file||files.has(file))return;files.add(file);for(const i of c.meta.outputs[file].imports)if(i.kind==='import-statement'&&!i.external)add(i.path)}add(keyFor(c.manifest.entry,c));add(keyFor(c.manifest.enhancements,c));for(const file of Object.keys(c.meta.outputs))if(/\/(?:auth-CYy33DK4|index-B9g8pWqs|activityTracker-CbPMjx4A|permission-C_Z--OsH)-/.test(file))add(file);return c.criticalPreloads=new Set([...files].map(file=>'/fast-ui/'+c.manifest.buildId+'/'+path.relative(path.join(root,'public/fast-ui'),path.resolve(root,file)).replaceAll('\\','/')));}
function preloads(c,pathname){
 const requested=new Set([keyFor(c.manifest.entry,c),keyFor(c.manifest.enhancements,c)]);
 const measured=require('./home-critical-assets.json').urls.filter(f=>!/(pdf-lib|echarts|auth-BBsZaxor|index-Do-kwv8k|DashboardDefault)/.test(f));
 const page={'/dashboard':['index-Do-kwv8k.js','DashboardDefault-DlL0udQC.js'],'/procurement/ai-purchase':['index-DRTnVvLv.js'],'/procurement/orders':['list-x__r5ydI.js']}[pathname]||[];
 const startup=['auth-CYy33DK4.js','index-B9g8pWqs.js','activityTracker-CbPMjx4A.js','permission-C_Z--OsH.js'];
 const origins=[...measured.map(f=>'public/native'+f),...[...page,...startup].map(f=>'public/native/assets/'+f)];
 for(const [file,meta]of Object.entries(c.meta.outputs))if(Object.keys(meta.inputs).some(input=>origins.includes(input.replaceAll('\\','/'))))requested.add(file);
 for(const [file]of Object.entries(c.meta.outputs))if(startup.some(name=>path.basename(file).startsWith(name.slice(0,-3)+'-')))requested.add(file);
 function expand(file){if(!file||!c.meta.outputs[file])return;for(const dependency of c.meta.outputs[file].imports.filter(d=>d.kind==='import-statement'&&!d.external))if(!requested.has(dependency.path)){requested.add(dependency.path);expand(dependency.path)}}
 for(const file of [...requested])expand(file);
 const routeMap=c.routeMap||(c.routeMap=require('./fast-routes.cjs').map(c));for(const file of routeMap[pathname]?.files||[])requested.add('public/fast-ui/'+file);
 return [...requested].filter(Boolean).map(file=>'/fast-ui/'+c.manifest.buildId+'/'+path.relative(path.join(root,'public/fast-ui'),path.resolve(root,file)).replaceAll('\\','/'));
}
function rewrite(html,url){const c=enabled(url);if(!c)return html;const prefix='/fast-ui/'+c.manifest.buildId+'/';
 // Keep map configuration before app execution without blocking HTML parsing
 // behind the page's parallel module downloads.
 html=html.replace('<script src="/amap-config.js"></script>','');
 html=html.replace('src="/assets/index-BD-cfb2U.js"','src="'+prefix+c.manifest.entry+'"');
 html=html.replace(/<link rel="modulepreload" crossorigin href="(?:\/assets\/[^"<>]+|\/startup-[^"<>]+)">/g,'');
 for(const f of ['modern.css','chain-workspace.css','design-system.css'])html=html.replace('<link rel="stylesheet" href="/'+f+'">','');
 const scripts=['design-system.js','home-workspace.js','enhance.js','simulation.js','upload.js','practice.js','history.js','business.js','bom-actions.js','data-template.js','warehouse-background.js','list-analysis.js','chain-workspace.js','design-polish.js','zones-layout.js','purpose-flow.js','action-contracts.js','account-flows.js','menu-composition.js'];
 for(const f of scripts)html=html.replace('<script defer src="/'+f+'"></script>','');
 const nativeStyle='<link rel="stylesheet" crossorigin href="/assets/index-Bf-F27GL.css">';
 if(!html.includes(nativeStyle))throw Error('Native base stylesheet contract changed');
 // Original order is workspace CSS -> native base CSS -> ElementPlus CSS.
 // Fetch the original dynamic ElementPlus stylesheet early at that same position.
 html=html.replace(nativeStyle,nativeStyle+'<link rel="stylesheet" crossorigin href="/assets/index-DbC0dCYL.css">');
 const links=preloads(c,url.pathname),route=c.routeMap[url.pathname],styleHints=(route?.css||[]).filter(p=>!p.endsWith('index-DbC0dCYL.css')&&!p.endsWith('index-Bf-F27GL.css')).map(p=>'<link rel="preload" as="style" crossorigin href="'+p+'">').join('');
 const critical=criticalPreloads(c);
 // The global bootstrap has the same same-origin credentials as enhance.js's
 // fetch. This is a one-navigation preload, not an API cache or business read.
 const tags='<link rel="preload" as="fetch" crossorigin fetchpriority="high" href="/__account/bootstrap"><script defer fetchpriority="high" src="/amap-config.js"></script><link rel="stylesheet" href="'+prefix+c.manifest.style+'"><link rel="preload" as="style" crossorigin href="/assets/index-Bf-F27GL.css"><link rel="preload" as="style" crossorigin href="/assets/index-DbC0dCYL.css">'+styleHints+links.map(u=>'<link rel="modulepreload" crossorigin fetchpriority="'+(critical.has(u)?'high':'auto')+'" href="'+u+'">').join('')+require('./fast-routes.cjs').intentScript(c,c.routeMap)+'<script type="module" crossorigin src="'+prefix+c.manifest.enhancements+'"></script>';
 return html.replace('<head>','<head>'+tags);
}
function asset(p){const match=p.match(/^\/fast-ui\/([a-f0-9]{64})\/((?:chunks\/)?[A-Za-z0-9_.-]+\.(?:js|css))$/);if(!match)return null;const dir=path.join(root,'public/fast-ui',match[1]);if(!fs.existsSync(path.join(dir,'manifest.json')))return null;const m=JSON.parse(fs.readFileSync(path.join(dir,'manifest.json'))),entry=m.files.find(f=>f.path===match[2]);if(!entry)return null;const file=path.join(dir,entry.path),bytes=fs.readFileSync(file);if(crypto.createHash('sha256').update(bytes).digest('hex')!==entry.sha256)throw Error('Fast UI resource changed');return{bytes,type:entry.path.endsWith('.css')?'text/css; charset=utf-8':'text/javascript; charset=utf-8'};}
exports.current=current;exports.rewrite=rewrite;exports.asset=asset;exports.preloads=preloads;
