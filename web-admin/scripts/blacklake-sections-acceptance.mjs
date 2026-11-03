// Actual local test UI, isolated browser, read-only source replay.
import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdirSync,writeFileSync } from 'node:fs';
import { resolve,join } from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..');
const out=join(process.env.LOCALAPPDATA,'Cretas','Blacklake','outputs','sections-27b-20261103');mkdirSync(out,{recursive:true});
const script="import json,os;from pathlib import Path;from blacklake.secrets import load_local_secret;s=load_local_secret(Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake/smartbi-test.dpapi');print(json.dumps({k:s[k] for k in ('username','password')}))";
const auth=JSON.parse(execFileSync('python',['-c',script],{cwd:join(root,'backend/python'),encoding:'utf8'}));
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000}});const page=await context.newPage();
const errors=[],blocked=[],answers=[];page.on('pageerror',e=>errors.push(e.message));
await context.route('**/*',route=>{
  const req=route.request(),url=new URL(req.url()),path=url.pathname.replace(/^\/smartbi-api(?=\/)/,'');
  if(!['127.0.0.1','localhost'].includes(url.hostname))return route.abort();
  if(['POST','PUT','PATCH','DELETE'].includes(req.method()) && ![
    '/api/mobile/auth/unified-login','/api/smartbi/blacklake/sections/followup',
  ].includes(path)){blocked.push(path);return route.abort();}return route.continue();
});
const result={success:false,sourceBusinessWrites:0,blockedMutationAttempts:blocked,pageErrors:errors,sectionAnswers:answers};let stage='login';
async function ask(sectionId){
  const panel=page.getByTestId('section-insight-'+sectionId);await panel.waitFor({timeout:60000});
  await panel.getByRole('button',{name:'这个数字怎么算的？',exact:true}).click();
  const responsePromise=page.waitForResponse(r=>r.url().includes('/sections/followup') && r.request().method()==='POST');
  await panel.getByRole('button',{name:'发送',exact:true}).click();
  await panel.locator('.message--assistant').filter({hasText:'计算口径'}).waitFor({timeout:170000});
  const reply=(await (await responsePromise).json()).data;
  assert.equal(reply.sectionId,sectionId);
  assert.equal(reply.ai.status,reply.ai.sectionRepresentativeFacts===0?'not_applicable':reply.ai.reviewedSectionFacts===reply.ai.sectionRepresentativeFacts?'complete':'partial');
  answers.push({sectionId,aiStatus:reply.ai.status,reviewed:reply.ai.reviewedSectionFacts,representatives:reply.ai.sectionRepresentativeFacts});
  await panel.screenshot({path:join(out,'section-'+sectionId+'.png')});
}
try{
  await page.goto('http://127.0.0.1:5178/blacklake-smartbi.html');
  await page.getByPlaceholder('请输入测试账号').fill(auth.username);await page.getByPlaceholder('请输入密码').fill(auth.password);
  await page.getByRole('button',{name:'登录分析',exact:true}).click();
  await page.getByRole('heading',{name:'六膳门 · 原料到成品成本分析',exact:true}).waitFor({timeout:60000});
  const rows=page.getByTestId('cost-projects').locator('.el-table__body-wrapper tbody tr');
  await rows.first().waitFor({timeout:60000});assert.equal(await rows.count(),16);
  stage='cost-conversations';
  for(const id of ['finished','product_cost','labor','material'])await ask(id);
  assert.equal(await page.getByTestId('section-insight-finished').locator('.message--assistant').count(),1);
  // Every panel owns its own conversation; changing tabs must not merge histories.
  stage='production';await page.getByRole('tab',{name:'生产与工序',exact:true}).click();await ask('production');
  stage='inventory';await page.getByRole('tab',{name:'库存与仓库',exact:true}).click();await ask('inventory');
  stage='coverage';await page.getByRole('tab',{name:'数据覆盖与口径',exact:true}).click();await ask('coverage');
  stage='alerts';await page.getByRole('tab',{name:'成本与数据异常',exact:true}).click();
  await page.getByTestId('local-ai-state').filter({hasText:'qwen3.8:27b'}).waitFor();
  await page.getByTestId('cost-alert-table').locator('tbody tr').first().waitFor();
  await page.screenshot({path:join(out,'cost-alerts-desktop.png'),fullPage:false});
  stage='mobile';await page.getByRole('tab',{name:'原料 → 成品成本',exact:true}).click();
  await page.setViewportSize({width:390,height:844});
  const material=page.getByTestId('section-insight-material');await material.scrollIntoViewIfNeeded();
  await material.getByRole('button',{name:'发送',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false);
  await material.screenshot({path:join(out,'section-material-mobile.png')});
  assert.equal(errors.length,0);assert.equal(blocked.length,0);
  result.success=true;result.eligibleRows=16;result.model='qwen3.8:27b';result.mobileOverflow=false;
}catch(e){result.error=e.message;result.stage=stage;await page.screenshot({path:join(out,'ui-failure.png'),fullPage:false});}
finally{writeFileSync(join(out,'ui-acceptance.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));await browser.close();}
process.exitCode=result.success?0:1;
