'use strict';
// Derive small, identical public contracts from the preserved compiler output.
// This only changes modern delivery; public/native is never written.
const fs=require('node:fs'),path=require('node:path');
const assets=path.join(__dirname,'../public/native/assets');
const read=name=>fs.readFileSync(path.join(assets,name),'utf8');
function sources(){
 const pdf=read('pdf-lib-DIyK-pLh.js'),start=pdf.indexOf('const JB="modulepreload"'),end=pdf.indexOf(';var Dt=Uint8Array');
 if(start<0||end<=start)throw Error('Preload compiler contract changed');
 const auth=read('auth-BBsZaxor.js'),a=auth.indexOf(',q={factory_super_admin:'),b=auth.indexOf('export{q as R,b as a,T as c,S as i};');
 if(a<0||b<=a)throw Error('Identity metadata compiler contract changed');
 const entry=read('index-BD-cfb2U.js'),t=entry.indexOf('G=["#'),k=entry.indexOf(';async function K()');
 if(t<0||k<=t)throw Error('Chart theme compiler contract changed');
 return {
  'startup-preload.js':pdf.slice(start,end)+';export{Tl as _};\n',
  'startup-auth.js':'const '+auth.slice(a+1,b)+'export{q as R,b as a,S as i};\n',
  'startup-chart-themes.js':'const '+entry.slice(t,k)+';export function apply(e){e.registerTheme("cretas",T);e.registerTheme("cretas-dark",q)}\n'
 };
}
function patch(file,text){
 const base=path.basename(file);
 // Do not change mixed PDF imports: their real PDF functionality remains lazy.
 text=text.replace(/import\{([^{}]+)\}from"\.\/pdf-lib-DIyK-pLh\.js";/g,(all,bindings)=>bindings.split(',').every(v=>/^_ as [\w$]+$/.test(v.trim()))?all.replace('./pdf-lib-DIyK-pLh.js','/startup-preload.js'):all);
 // Keep the original chart re-export module for consumers that need `c`.
 text=text.replace(/import\{([^{}]+)\}from"\.\/auth-BBsZaxor\.js";/g,(all,bindings)=>bindings.split(',').every(v=>/^[Rai] as [\w$]+$/.test(v.trim()))?all.replace('./auth-BBsZaxor.js','/startup-auth.js'):all);
 // These compiler-only dependency edges contain no required initialization.
 // Named imports on real PDF/chart pages are deliberately retained.
 text=text.replaceAll('import"./pdf-lib-DIyK-pLh.js";','').replaceAll('import"./echarts-VthzOFJC.js";','').replaceAll('import"./auth-BBsZaxor.js";','import"/startup-auth.js";');
 // Vite's numeric dependency tables are hints, not execution. Keep the table
 // indices intact while removing eager hints for heavyweight shared barrels.
 text=text.replaceAll('"assets/pdf-lib-DIyK-pLh.js"','"startup-preload.js"').replaceAll('"assets/auth-BBsZaxor.js"','"startup-auth.js"').replaceAll('"assets/echarts-VthzOFJC.js"','"startup-chart-themes.js"');
 if(base==='index-BD-cfb2U.js'){
  const before='e.use(w),K(),e.mount("#app")';
  if(!text.includes(before))throw Error('Bootstrap compiler contract changed');
  text=text.replace(before,'e.use(w),e.mount("#app")');
 }
 if(base==='echarts-BAv7SgNl.js'){
  if(!text.includes('export{Fe as default};'))throw Error('Chart compiler contract changed');
  text='import{apply as __applyCretasThemes}from"/startup-chart-themes.js";'+text.replace('export{Fe as default};','__applyCretasThemes(Fe);export{Fe as default};');
 }
 return text;
}
exports.sources=sources;exports.patch=patch;
if(require.main===module){for(const [name,source]of Object.entries(sources()))fs.writeFileSync(path.join(__dirname,'../public',name),source);console.log('Generated 3 source-derived startup slices; native preserved.');}
