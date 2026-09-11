const {chromium}=require('../../../web-admin/node_modules/playwright');
const fs=require('fs'),path=require('path'),{pathToFileURL}=require('url');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:720},deviceScaleFactor:2});
  await page.goto(pathToFileURL(path.join(__dirname,'deck-v2.html')).href);
  await page.evaluate(()=>document.fonts.ready);await page.waitForFunction(()=>[...document.images].every(i=>i.complete&&i.naturalWidth));
  const report=await page.evaluate(()=>[...document.querySelectorAll('.page2,.page3,.page4')].map(s=>{
   const sr=s.getBoundingClientRect(),f=s.querySelector('.foot').getBoundingClientRect(),bar=s.querySelector('.past-problem');
   const nodes=[...s.querySelectorAll('.b2,.setup-demo,.cap2,.st,.res,.ai,.map,.rt')];
   return{page:s.className,barHeight:bar.getBoundingClientRect().height,bodyBottom:Math.max(...nodes.map(e=>e.getBoundingClientRect().bottom-sr.top)),footerTop:f.top-sr.top,overflow:s.scrollWidth>s.clientWidth||s.scrollHeight>s.clientHeight,cardsOverflow:[...s.querySelectorAll('.b2,.setup-body')].some(e=>e.scrollHeight>e.clientHeight)};
  }));
  console.log(JSON.stringify(report,null,2));
  fs.writeFileSync(path.join(__dirname,'pain-alpha-layout.json'),JSON.stringify(report,null,2));
  await page.pdf({path:path.join(__dirname,'pain-alpha-rendered.pdf'),preferCSSPageSize:true,printBackground:true});
  for(let i=2;i<=4;i++)await page.locator('.page'+i).screenshot({path:path.join(__dirname,`pain-alpha-page-${i}.png`)});
  if(report.some(r=>r.overflow||r.cardsOverflow||r.bodyBottom>r.footerTop-8))throw Error('Layout fails');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
