const {chromium}=require('../../../web-admin/node_modules/playwright');
const path=require('path');const fs=require('fs');const {pathToFileURL}=require('url');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:720},deviceScaleFactor:2});
  await page.goto(pathToFileURL(path.join(__dirname,'deck-v2.html')).href);
  await page.evaluate(()=>document.fonts.ready);
  await page.waitForFunction(()=>[...document.images].every(i=>i.complete));
  const checks=await page.evaluate(()=>[...document.querySelectorAll('section.s')].map(s=>{
   const sr=s.getBoundingClientRect(),foot=s.querySelector('.foot')?.getBoundingClientRect();
   const main=s.querySelector('.content-fit'),group=s.querySelector('.pg2,.acc,.prod');
   return {page:s.className,overflow:s.scrollWidth>s.clientWidth||s.scrollHeight>s.clientHeight,groupBottom:group?group.getBoundingClientRect().bottom-sr.top:null,wrapperBottom:main?main.getBoundingClientRect().bottom-sr.top:null,footerTop:foot?foot.top-sr.top:null};
  }));
  const cardOverflow=await page.evaluate(()=>[...document.querySelectorAll('.page2 .b2')].map(s=>({height:s.clientHeight,scrollHeight:s.scrollHeight,overflow:s.scrollHeight>s.clientHeight})));
  const bad=checks.filter(c=>c.overflow||(c.groupBottom&&c.groupBottom>c.footerTop-8)||(c.groupBottom&&c.groupBottom>c.wrapperBottom+1));
  if(bad.length||cardOverflow.some(c=>c.overflow))throw new Error(JSON.stringify({bad,cardOverflow,checks}));
  const pain=await page.locator('.restored-pain').count();if(pain!==3)throw new Error('Pain count '+pain);
  fs.writeFileSync(path.join(__dirname,'pricing-pain-layout.json'),JSON.stringify({checks,cardOverflow,pain},null,2));
  await page.pdf({path:path.join(__dirname,'pricing-pain-rendered.pdf'),preferCSSPageSize:true,printBackground:true,displayHeaderFooter:false});
  console.log('LAYOUT_OVERFLOW=0\nFOOTER_OVERLAP=0\nPAIN_BARS=3');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
