// Read public dynamic docs in an isolated browser; emit only requested schema text.
import { chromium } from '@playwright/test';
const browser=await chromium.launch({headless:false});
try {
  const page=await browser.newPage();
  await page.goto('https://blacklake.feishu.cn/docs/doccn4ngN8QPClafs1duryGaLed',{waitUntil:'domcontentloaded'});
  await page.waitForTimeout(2500);
  const topic=process.argv[2];
  if(topic) {
    const entry=page.getByText(topic,{exact:true}).first();
    if(await entry.count()) await entry.click();
    await page.waitForTimeout(800);
    for(let i=0;i<4;i++) {
      await page.locator('.etherpad-container-wrapper').evaluate(el=>{el.scrollTop+=1200;}).catch(()=>{});
      await page.waitForTimeout(250);
    }
  }
  const lines=(await page.locator('body').innerText()).split('\n');
  const terms=topic ? /costUnitPrice|changedCostAmountSigned|associatedBizOrder|originDetail|unitQty|parentBaseQty|lossRate|projectId|projectCode|工单ID|用量|损耗|单价来源/ : /查询.*(出入库|用料|库存|工单|产品|BOM)/;
  const selected=[];
  lines.forEach((line,i)=>{if(terms.test(line)) selected.push(lines.slice(i,i+(topic?5:1)).join(' | '));});
  console.log(JSON.stringify({title:await page.title(),schema:selected.slice(-50).filter(t=>!/(password|token|X-AUTH|Authorization|phone|密码|令牌|手机号)/i.test(t)).map(t=>t.replace(/[A-Za-z0-9_.-]{40,}/g,'[redacted]'))}));
} finally {await browser.close();}
