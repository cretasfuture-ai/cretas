/* Static website QA in an isolated browser; never touches business services. */
const fs=require('node:fs/promises');
const path=require('node:path');
const assert=require('node:assert/strict');
const {createHash}=require('node:crypto');
const {chromium}=require(process.env.SHOWCASE_PLAYWRIGHT_PATH||'playwright');
const origin=process.env.SHOWCASE_PREVIEW_ORIGIN||'http://127.0.0.1:31814';
assert(['127.0.0.1','localhost'].includes(new URL(origin).hostname),'Local preview only');
const out=process.env.SHOWCASE_VERIFY_DIR;assert(out,'SHOWCASE_VERIFY_DIR required');
(async()=>{
 await fs.mkdir(out,{recursive:true});const browser=await chromium.launch({headless:true});
 const results=[];const failures=[];
 try{
  for(const width of [1440,1024,768,390,320]){
   const context=await browser.newContext({viewport:{width,height:1000},reducedMotion:'reduce',acceptDownloads:true});
   await context.route('**/*',route=>{
    const request=route.request();
    if(!['GET','HEAD','OPTIONS'].includes(request.method()))return route.abort();
    return route.continue();
   });
   for(const route of ['/','/solutions-factory.html','/factory-onboarding.html']){
    const page=await context.newPage();const errors=[];const badResponses=[];
    page.on('pageerror',error=>errors.push(error.message));
    page.on('response',response=>{if(response.url().startsWith(origin)&&response.status()>=400)badResponses.push({url:response.url(),status:response.status()});});
    const response=await page.goto(origin+route,{waitUntil:'networkidle'});assert.equal(response.status(),200);
    await page.evaluate(async()=>{await document.fonts.ready;for(const image of document.images){image.loading='eager';await image.decode().catch(()=>{});}});
    const metrics=await page.evaluate(()=>({
     horizontalOverflow:document.documentElement.scrollWidth>innerWidth+1,
     pdfLinks:document.querySelectorAll('a[download], a[href$=".pdf"]').length,
     brokenImages:[...document.images].filter(i=>i.hasAttribute('src')&&(!i.complete||!i.naturalWidth)).map(i=>i.getAttribute('src')),
     title:document.title,
     scopeOverflow:[...document.querySelectorAll('.fb-module *, .fb-page main *, .fb-page nav *')].filter(el=>{const box=el.getBoundingClientRect();return box.right>innerWidth+1||box.left< -1;}).slice(0,8).map(el=>({tag:el.tagName,cls:el.className})),
    }));
    if(route==='/factory-onboarding.html'){
     assert.equal(await page.locator('.fb-hero-copy').evaluate(el=>getComputedStyle(el).animationName),'none','Reduced motion must suppress the entrance');
     const field=page.locator('.fb-field').first();const initialOpen=await field.getAttribute('open');await field.locator('summary').click();assert.notEqual(await field.getAttribute('open'),initialOpen);await field.locator('summary').click();
     for(const label of ['经营指标','批次追溯','成本明细']){
      const tab=page.getByRole('tab',{name:label,exact:true});await tab.click();assert.equal(await tab.getAttribute('aria-selected'),'true');
      const panel=page.getByRole('tabpanel');assert.equal(await panel.count(),1);assert(await panel.isVisible());
     }
     await page.getByRole('tab',{name:'成本明细',exact:true}).focus();await page.keyboard.press('ArrowRight');
     assert.equal(await page.getByRole('tab',{name:'经营指标',exact:true}).getAttribute('aria-selected'),'true');
     await page.keyboard.press('End');assert.equal(await page.getByRole('tab',{name:'成本明细',exact:true}).getAttribute('aria-selected'),'true');
     await page.keyboard.press('Home');assert.equal(await page.getByRole('tab',{name:'经营指标',exact:true}).getAttribute('aria-selected'),'true');
     const opener=page.getByRole('tabpanel').locator('a[data-image-title]').first();await opener.click();
     const viewer=page.getByRole('dialog');assert(await viewer.isVisible());
     await viewer.locator('img').evaluate(image=>image.decode());
     await viewer.getByRole('button',{name:'原图大小',exact:true}).click();
     assert.equal(await viewer.getByRole('button',{name:'适应窗口',exact:true}).getAttribute('aria-pressed'),'true');
     assert(await viewer.locator('img').evaluate(image=>image.clientWidth===image.naturalWidth));
     assert(await viewer.evaluate(el=>el.getBoundingClientRect().left>=0&&el.getBoundingClientRect().right<=innerWidth));
     await page.keyboard.press('Escape');assert.equal(await page.getByRole('dialog').count(),0);assert(await opener.evaluate(el=>el===document.activeElement));
     if(width<=900){
      const menu=page.locator('.fb-mobile-menu');await menu.locator('summary').click();assert(await menu.locator('a[href="#cooperate"]').isVisible());
      await menu.locator('a[href="#cooperate"]').click();assert.equal(await menu.getAttribute('open'),null);
     }
     await page.locator('.fb-hero a[href="#cooperate"]').click();assert.equal(new URL(page.url()).hash,'#cooperate');
     // 能力介绍 PDF 已下线 (2026-10-20): 页面上不能再有下载入口或 .pdf 链接
     assert.equal(await page.locator('a[download], a[href$=".pdf"]').count(),0);
     await page.evaluate(()=>scrollTo(0,0));
    }
    const name=route==='/'?'home':route.replace(/\W+/g,'-');
    await page.screenshot({path:path.join(out,`${width}${name}.png`),fullPage:true});
    const pass=!metrics.horizontalOverflow&&!metrics.pdfLinks&&!metrics.brokenImages.length&&!metrics.scopeOverflow.length&&!errors.length&&!badResponses.length;
    const result={width,route,status:pass?'PASS':'FAIL',...metrics,errors,badResponses};results.push(result);
    if(!pass)failures.push(result);await page.close();
   }
   await context.close();
  }
  const fallback=await browser.newContext({viewport:{width:390,height:900},javaScriptEnabled:false});
  const fallbackPage=await fallback.newPage();await fallbackPage.goto(origin+'/factory-onboarding.html',{waitUntil:'networkidle'});
  assert.equal(await fallbackPage.locator('.fb-proof:visible').count(),3,'All demonstrations must remain visible without JavaScript');
  await fallbackPage.locator('.fb-mobile-menu summary').click();assert(await fallbackPage.locator('.fb-mobile-menu a[href="#cooperate"]').isVisible());
  const imageLink=fallbackPage.locator('.fb-proof a[data-image-title]').first();const imageUrl=await imageLink.getAttribute('href');
  assert.equal((await fallbackPage.request.get(origin+imageUrl)).status(),200);
  results.push({route:'/factory-onboarding.html',width:390,javaScript:false,status:'PASS',description:'All three demonstrations, native navigation and image fallback usable'});
  await fallback.close();
  const animated=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'no-preference'});
  const animatedPage=await animated.newPage();await animatedPage.goto(origin+'/factory-onboarding.html',{waitUntil:'networkidle'});
  assert.equal(await animatedPage.locator('.fb-hero-copy').evaluate(el=>getComputedStyle(el).opacity),'1','Entrance must not gate readable content');
  await animatedPage.locator('.fb-hero-copy').evaluate(el=>Promise.all(el.getAnimations().map(animation=>animation.finished)));
  assert(await animatedPage.locator('.fb-hero-copy').evaluate(el=>{const transform=getComputedStyle(el).transform;return transform==='none'||new DOMMatrix(transform).isIdentity;}),'Entrance must finish without displacement');
  await animatedPage.screenshot({path:path.join(out,'desktop-motion.png')});
  results.push({route:'/factory-onboarding.html',width:1440,motion:'no-preference',status:'PASS',description:'Content remains visible and entrance completes'});
  await animated.close();
 }finally{await browser.close();}
 const receipt={status:failures.length?'FAIL':'PASS',testedAt:new Date().toISOString(),origin,results,businessMutations:0};
 await fs.writeFile(path.join(out,'responsive-receipt.json'),JSON.stringify(receipt,null,2));
 console.log(JSON.stringify({status:receipt.status,cases:results.length,failures}));
 if(failures.length)process.exitCode=1;
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
