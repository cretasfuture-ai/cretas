const {chromium}=require('../../../web-admin/node_modules/playwright');
const path=require('path');
const fs=require('fs');
const {pathToFileURL}=require('url');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:690},deviceScaleFactor:2});
  await page.goto(pathToFileURL(path.join(__dirname,'mock-cost.html')).href);
  await page.waitForFunction(()=>window.MOCK_READY===true);
  await page.evaluate(()=>document.fonts.ready);
  const mock=await page.evaluate(()=>({height:document.documentElement.scrollHeight,width:document.documentElement.scrollWidth,viewport:innerHeight}));
  if(mock.height>690||mock.width>1440)throw new Error('Mock overflow '+JSON.stringify(mock));
  await page.screenshot({path:path.join(__dirname,'mock-cost.png'),fullPage:false});
  await page.setViewportSize({width:1280,height:720});
  await page.goto(pathToFileURL(path.join(__dirname,'deck-v2.html')).href);
  await page.evaluate(()=>document.fonts.ready);
  await page.waitForFunction(()=>Array.from(document.images).every(i=>i.complete));
  const checks=await page.evaluate(()=>[...document.querySelectorAll('.page2,.page3,.page4,.setup-body,.setup-demo,.page2 .b2,.page3 .ai,.page4 .map,.page4 .rt')].map(s=>({selector:s.className,overflow:s.scrollWidth>s.clientWidth||s.scrollHeight>s.clientHeight,bottom:s.getBoundingClientRect().bottom-s.closest('section').getBoundingClientRect().top})));
  const headers=await page.locator('.pain').count();
  if(headers)throw new Error('Explanation bars remain');
  if(checks.some(c=>c.overflow))throw new Error(JSON.stringify(checks));
  fs.writeFileSync(path.join(__dirname,'roomier-layout.json'),JSON.stringify({mock,checks},null,2));
  await page.pdf({path:path.join(__dirname,'roomier-rendered.pdf'),preferCSSPageSize:true,printBackground:true,displayHeaderFooter:false});
  console.log('MOCK_AND_PAGE_OVERFLOW=0');
  console.log('EXPLANATION_BARS=0');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
