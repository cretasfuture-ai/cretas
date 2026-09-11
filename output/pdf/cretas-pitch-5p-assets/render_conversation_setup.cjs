const {chromium}=require('../../../web-admin/node_modules/playwright');
const path=require('path');
const fs=require('fs');
const {pathToFileURL}=require('url');
(async()=>{
  const browser=await chromium.launch({headless:true});
  const page=await browser.newPage({viewport:{width:1280,height:720},deviceScaleFactor:2});
  await page.goto(pathToFileURL(path.join(__dirname,'deck-v2.html')).href);
  await page.evaluate(()=>document.fonts.ready);
  await page.waitForFunction(()=>Array.from(document.images).every(i=>i.complete));
  const checks=await page.evaluate(()=>[...document.querySelectorAll('.page1,.page2,.setup-body,.setup-demo,.page2 .b2,.cover .four>div:first-child')].map(s=>({selector:s.className||s.tagName,width:s.clientWidth,height:s.clientHeight,scrollWidth:s.scrollWidth,scrollHeight:s.scrollHeight,overflow:s.scrollWidth>s.clientWidth||s.scrollHeight>s.clientHeight})));
  fs.writeFileSync(path.join(__dirname,'conversation-setup-layout.json'),JSON.stringify(checks,null,2));
  if(checks.some(c=>c.overflow))throw new Error(JSON.stringify(checks));
  await page.pdf({path:path.join(__dirname,'conversation-setup-rendered.pdf'),preferCSSPageSize:true,printBackground:true,displayHeaderFooter:false});
  await browser.close();
  console.log('HTML_LAYOUT_OVERFLOW=0');
})().catch(e=>{console.error(e);process.exit(1)});
