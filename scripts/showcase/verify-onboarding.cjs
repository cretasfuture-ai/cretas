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
  for(const width of [1440,1024,390,320]){
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
     brokenImages:[...document.images].filter(i=>!i.complete||!i.naturalWidth).map(i=>i.getAttribute('src')),
     title:document.title,
     scopeOverflow:[...document.querySelectorAll('.fb-module *, .fb-page main *, .fb-page nav *')].filter(el=>{const box=el.getBoundingClientRect();return box.right>innerWidth+1||box.left< -1;}).slice(0,8).map(el=>({tag:el.tagName,cls:el.className})),
    }));
    if(route==='/factory-onboarding.html'){
     const field=page.locator('.fb-field').first();await field.locator('summary').click();assert(await field.getAttribute('open')!==null);await field.locator('summary').click();
     await page.locator('a[href="#cooperate"]').first().click();assert.equal(new URL(page.url()).hash,'#cooperate');
     const [download]=await Promise.all([page.waitForEvent('download'),page.locator('a[download]').first().click()]);
     const destination=path.join(out,`download-${width}.pdf`);await download.saveAs(destination);
     const actual=await fs.readFile(destination);assert(actual.subarray(0,5).toString()==='%PDF-');
     const expected=await fs.readFile(path.resolve(__dirname,'../../platform/downloads/cretas-factory-onboarding.pdf'));
     assert.equal(createHash('sha256').update(actual).digest('hex'),createHash('sha256').update(expected).digest('hex'));
     await page.evaluate(()=>scrollTo(0,0));
    }
    const name=route==='/'?'home':route.replace(/\W+/g,'-');
    await page.screenshot({path:path.join(out,`${width}${name}.png`),fullPage:true});
    const pass=!metrics.horizontalOverflow&&!metrics.brokenImages.length&&!metrics.scopeOverflow.length&&!errors.length&&!badResponses.length;
    const result={width,route,status:pass?'PASS':'FAIL',...metrics,errors,badResponses};results.push(result);
    if(!pass)failures.push(result);await page.close();
   }
   await context.close();
  }
 }finally{await browser.close();}
 const receipt={status:failures.length?'FAIL':'PASS',testedAt:new Date().toISOString(),origin,results,businessMutations:0};
 await fs.writeFile(path.join(out,'responsive-receipt.json'),JSON.stringify(receipt,null,2));
 console.log(JSON.stringify({status:receipt.status,cases:results.length,failures}));
 if(failures.length)process.exitCode=1;
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
