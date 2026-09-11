const {chromium}=require('../../../web-admin/node_modules/playwright');
const path=require('path');const fs=require('fs');const {pathToFileURL}=require('url');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:720},deviceScaleFactor:2});
  await page.goto(pathToFileURL(path.join(__dirname,'deck-v2.html')).href);
  await page.evaluate(()=>document.fonts.ready);
  await page.waitForFunction(()=>[...document.images].every(i=>i.complete));
  const bars=await page.locator('.pain,.restored-pain').count();if(bars!==0)throw new Error('Top bars must remain absent');
  const checks=await page.evaluate(()=>[...document.querySelectorAll('.page2,.page3,.page4')].map(s=>{
   const sr=s.getBoundingClientRect(),f=s.querySelector('.foot').getBoundingClientRect(),w=s.querySelector('.content-fit').getBoundingClientRect();
   const elements=[...s.querySelectorAll('.b2,.setup-demo,.cap2,.st,.res,.ai,.map,.rt')];
   return {page:s.className,overflow:s.scrollWidth>s.clientWidth||s.scrollHeight>s.clientHeight,bodyBottom:Math.max(...elements.map(e=>e.getBoundingClientRect().bottom-sr.top)),wrapperBottom:w.bottom-sr.top,footerTop:f.top-sr.top,cardsOverflow:[...s.querySelectorAll('.b2')].some(e=>e.scrollHeight>e.clientHeight)};
  }));
  if(checks.some(c=>c.overflow||c.cardsOverflow||c.bodyBottom>c.footerTop-8||c.bodyBottom>c.wrapperBottom+1))throw new Error(JSON.stringify(checks));
  fs.writeFileSync(path.join(__dirname,'final-no-bars-layout.json'),JSON.stringify(checks,null,2));
  await page.pdf({path:path.join(__dirname,'final-no-bars-rendered.pdf'),preferCSSPageSize:true,printBackground:true,displayHeaderFooter:false});
  console.log('TOP_EXPLANATION_BARS=0\nLAYOUT_OVERFLOW=0\nFOOTER_OVERLAP=0');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
