/* Local, read-only screenshots. Uses a dedicated browser, never the user's tab. */
const fs=require('node:fs/promises');const path=require('node:path');
const assert=require('node:assert/strict');
const {chromium}=require(process.env.SHOWCASE_PLAYWRIGHT_PATH||'playwright');
const origin=process.env.SHOWCASE_APP_ORIGIN||'http://127.0.0.1:31764';
assert(['localhost','127.0.0.1'].includes(new URL(origin).hostname),'Local demos only');
const out=process.env.SHOWCASE_CAPTURE_DIR;assert(out,'SHOWCASE_CAPTURE_DIR required');
const platformState=process.env.SHOWCASE_PLATFORM_STATE;const factoryState=process.env.SHOWCASE_FACTORY_STATE;
assert(platformState&&factoryState,'Private states from dedicated screenshot login required');
const demos=[
 {key:'conversation',state:platformState,url:'/factory-onboarding?factoryId=FOOD_9999_001',selector:'.page-container',expected:'当前只决定这一件事'},
 {key:'indicators',state:factoryState,url:'/analytics/indicators?periodStart=2026-10-16&periodEnd=2026-10-16',selector:'.app-content',expected:'完整生产成本'},
 {key:'traceability',state:factoryState,url:'/production/traceability?batchNumber=PB-PLAN-1792160828548-2A54CFD5-28631',selector:'.app-content',expected:'PB-PLAN-1792160828548-2A54CFD5-28631'},
 {key:'cost',state:factoryState,url:'/production/batches/15',selector:'.app-content',expected:'128.33'},
];
const selected=(process.env.SHOWCASE_CAPTURE_ONLY||'').split(',').filter(Boolean);
assert(selected.every(key=>demos.some(demo=>demo.key===key)),'Unknown capture key');
(async()=>{
 await fs.mkdir(out,{recursive:true});const browser=await chromium.launch({headless:true});const results=[];
 try{
  for(const demo of demos){
   if(selected.length&&!selected.includes(demo.key))continue;
   const context=await browser.newContext({viewport:{width:1600,height:1120},deviceScaleFactor:1.5,storageState:demo.state});
   let blocked=[];let reads=[];
   await context.route('**/*',async route=>{
    const r=route.request(),u=new URL(r.url());
    if(!['localhost','127.0.0.1'].includes(u.hostname)&&!['data:','blob:'].includes(u.protocol))return route.abort();
    if(!['GET','HEAD','OPTIONS'].includes(r.method())){blocked.push({method:r.method(),path:u.pathname});return route.abort();}
    if(u.pathname.startsWith('/api/'))reads.push(u.pathname);return route.continue();
   });
   const page=await context.newPage();await page.goto(origin+demo.url,{waitUntil:'networkidle'});
   await page.getByText(demo.expected,{exact:false}).first().waitFor({timeout:30000});await page.evaluate(()=>document.fonts.ready);
   const text=await page.locator('body').innerText();assert(!text.includes('Network Error')&&!text.includes('页面不存在')&&!page.url().includes('/login'),demo.key+' is not usable');
   const target=page.locator(demo.selector).first();const file=path.join(out,demo.key+'.png');
   // Expand the dedicated capture viewport so inner scroll containers are not cut off.
   const extraHeight=await target.evaluate(el=>Math.max(0,el.scrollHeight-el.clientHeight));
   if(extraHeight)await page.setViewportSize({width:1600,height:1120+extraHeight+24});
   await page.evaluate(()=>window.scrollTo(0,0));const box=await target.boundingBox();assert(box);
   await page.screenshot({path:file,clip:{x:box.x,y:box.y,width:box.width,height:box.height}});
   if(demo.key==='cost'){
    const card=page.getByText('成本明细',{exact:true}).locator('xpath=ancestor::div[contains(concat(" ",normalize-space(@class)," ")," el-card ")][1]');
    assert.equal(await card.count(),1);await card.screenshot({path:path.join(out,'cost-detail.png')});
   }
   await fs.writeFile(path.join(out,demo.key+'.txt'),text);
   assert.equal(blocked.length,0,'Unexpected write-shaped request');
   results.push({key:demo.key,url:page.url(),file,scope:demo.selector,blocked,readRequests:reads,successfulBusinessMutations:0});await context.close();
  }
 }finally{await browser.close();}
 const receipt=selected.length?'capture-receipt-'+selected.join('-')+'.json':'capture-receipt.json';
 await fs.writeFile(path.join(out,receipt),JSON.stringify({status:'PASS',capturedAt:new Date().toISOString(),source:'Real local application, synthetic demonstration data',browser:'Dedicated local Chromium; existing user and shared browser profiles untouched',results},null,2));
 console.log(JSON.stringify({status:'PASS',screenshots:results.map(x=>x.key),successfulBusinessMutations:0}));
})().catch(e=>{console.error(e.message);process.exit(1)});
