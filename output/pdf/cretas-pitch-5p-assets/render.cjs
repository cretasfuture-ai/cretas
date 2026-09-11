const {chromium}=require('../../../web-admin/node_modules/playwright');
const fs=require('fs');const path=require('path');const {pathToFileURL}=require('url');
const root=__dirname;
(async()=>{
 const browser=await chromium.launch({headless:true});
 const ctx=await browser.newContext({viewport:{width:1440,height:710},deviceScaleFactor:2});
 const page=await ctx.newPage(); const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const reports=[];
 for(const [kind,height] of [['usage',710],['cost',570]]){
  await page.setViewportSize({width:1440,height});
  await page.goto(pathToFileURL(path.join(root,`mock-${kind}.html`)).href);
  await page.waitForFunction(()=>window.MOCK_READY===true);
  await page.evaluate(()=>document.fonts.ready);await page.waitForTimeout(300);
  const info=await page.evaluate(()=>({text:document.body.innerText,width:innerWidth,height:innerHeight,dpr:devicePixelRatio,scrollWidth:document.documentElement.scrollWidth,scrollHeight:document.documentElement.scrollHeight,tableBodyFont:getComputedStyle(document.querySelector('.el-table .cell')).fontSize,chartBars:document.querySelectorAll('.baritem').length}));
  if(/[¥￥$]/.test(info.text))throw new Error('Currency in mock');
  if(info.scrollWidth>1440||info.scrollHeight>height)throw new Error('Mock overflow: '+JSON.stringify(info));
  await page.screenshot({path:path.join(root,`mock-${kind}.png`),fullPage:false});reports.push({kind,...info});
 }
 await page.setViewportSize({width:1280,height:720});
 await page.goto(pathToFileURL(path.join(root,'deck-v2.html')).href);await page.evaluate(()=>document.fonts.ready);await page.waitForTimeout(300);
 const overflow=await page.evaluate(()=>[...document.querySelectorAll('section.s')].map((s,i)=>({page:i+1,scrollWidth:s.scrollWidth,clientWidth:s.clientWidth,scrollHeight:s.scrollHeight,clientHeight:s.clientHeight})));
 for(let i=0;i<5;i++)await page.locator('section.s').nth(i).screenshot({path:path.join(root,`page-${i+1}.png`)});
 await page.pdf({path:path.resolve(root,'../白垩纪Cretas-四件事-5页-V2.pdf'),preferCSSPageSize:true,printBackground:true,displayHeaderFooter:false});
 fs.writeFileSync(path.join(root,'render-evidence.json'),JSON.stringify({errors,reports,overflow},null,2));
 if(errors.length)throw new Error(errors.join('\n'));
 console.log(JSON.stringify({mockScreenshots:reports.map(({kind,width,height,dpr,tableBodyFont,chartBars})=>({kind,width,height,dpr,tableBodyFont,chartBars})),overflow,pdf:'白垩纪Cretas-四件事-5页-V2.pdf'}));
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
