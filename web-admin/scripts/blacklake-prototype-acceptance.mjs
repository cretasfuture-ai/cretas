// Local test only: real data, real authority and Cretas follow-up metadata.
import {chromium} from '@playwright/test';
import {mkdirSync,writeFileSync} from 'node:fs';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'),out=join(process.env.LOCALAPPDATA,'Cretas','Blacklake','outputs','prototype-20261104');mkdirSync(out,{recursive:true});
const browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
const errors=[],blocked=[],metadataPaths=new Set(),answers=[];
let baseline,metadataWrites=0,stage='login';page.on('pageerror',e=>errors.push(e.message));
page.on('response',async r=>{if(r.request().method()==='GET' && r.url().endsWith('/blacklake/analysis') && r.status()===200){const value=await r.json();baseline=value.data;for(const a of baseline.costAlerts.items)metadataPaths.add('/api/smartbi/blacklake/alerts/'+encodeURIComponent(a.id)+'/followup');}});
await context.route('**/*',route=>{
 const req=route.request(),url=new URL(req.url()),path=url.pathname.replace(/^\/smartbi-api(?=\/)/,'');
 if(!['127.0.0.1','localhost'].includes(url.hostname))return route.abort();
 if(['POST','PUT','PATCH','DELETE'].includes(req.method())){
   if(req.method()==='POST' && metadataPaths.has(path)){metadataWrites++;return route.continue();}
   if(!['/__blacklake/local-session','/api/mobile/auth/unified-login','/api/smartbi/blacklake/sections/followup'].includes(path)){blocked.push(path);return route.abort();}
 }return route.continue();
});
const nav=label=>page.getByRole('navigation',{name:'',exact:true}).getByRole('button',{name:label,exact:true});
async function ask(id){
 const panel=page.getByTestId('section-insight-'+id);await panel.waitFor();
 await panel.getByRole('button',{name:'继续追问',exact:true}).click();
 await panel.getByRole('button',{name:'这个数字怎么算的？',exact:true}).click();
 const pending=page.waitForResponse(r=>r.url().endsWith('/sections/followup')&&r.request().method()==='POST');
 await panel.getByRole('button',{name:'发送',exact:true}).click();
 const response=await pending;assert.equal(response.status(),200);const data=(await response.json()).data;assert.equal(data.sectionId,id);
 await panel.locator('.message--assistant').filter({hasText:'计算口径'}).waitFor();answers.push(id);
}
const result={success:false,prototype:'samples-v5',sourceBusinessWrites:0,blockedMutationAttempts:blocked,pageErrors:errors,sectionAnswers:answers};
try{
 await page.goto('http://127.0.0.1:5178/blacklake-smartbi.html');
 await page.getByRole('heading',{name:'成本核算概览',exact:true}).waitFor({timeout:90000});
 assert.equal(await page.getByPlaceholder('请输入密码').count(),0);
 await page.getByTestId('cost-projects').locator('tbody tr').first().waitFor();
 assert.equal(await page.getByTestId('cost-projects').locator('.el-table__body-wrapper tbody tr').count(),16);
 assert.equal(await page.locator('.followup-panel:visible').count(),0);
 assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue('--c-primary').trim()),'#1b65a8');
 assert.equal(await page.locator('[data-prototype="samples-v5"]').count(),1);
 await page.locator('.unit-cost-card canvas').first().waitFor();
 const chart=await page.evaluate(async()=>{const engine=(await import('/src/utils/echarts.ts')).default;
   const dom=document.querySelector('.unit-cost-card [_echarts_instance_]');const value=engine.getInstanceByDom(dom)?.getOption();
   return {series:value?.series?.length,syntheticMarks:value?.series?.filter(s=>s.markPoint?.data?.length).length||0,toolbox:!!value?.toolbox?.length};});
 assert.equal(chart.series,2);assert.equal(chart.syntheticMarks,0);assert.equal(chart.toolbox,false);
 await page.screenshot({path:join(out,'01-overview-desktop.png'),fullPage:false});
 stage='sidebar';await page.getByRole('button',{name:'收起导航',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('.prototype-sidebar').getBoundingClientRect().width<80);
 assert.equal(await page.locator('.prototype-sidebar .nav-label').first().evaluate(el=>getComputedStyle(el).opacity),'0');
 await page.getByRole('button',{name:'展开导航',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('.prototype-sidebar').getBoundingClientRect().width>225);
 stage='section-conversations';for(const id of ['finished','product_cost','labor','material'])await ask(id);
 await nav('生产与工序').click();await ask('production');await nav('库存与仓库').click();await ask('inventory');await nav('数据覆盖与口径').click();await ask('coverage');
 stage='progress';await nav('异常与跟进').click();
 await page.getByTestId('cost-alert-table').locator('tbody tr').first().waitFor();
 const rows=page.locator('[data-alert-id]');let selected;
 for(const row of await rows.all()){const id=await row.getAttribute('data-alert-id');const previous=baseline.alertFollowups?.[id];if(!previous||previous.note.startsWith('界面验收标记')){selected={id,row};break;}}
 assert(selected,'No test-owned follow-up candidate available');
 await selected.row.getByRole('button',{name:'记录跟进',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'记录异常跟进'});await dialog.locator('.el-select').click();await page.getByRole('option',{name:'待补资料',exact:true}).click();
 await dialog.getByRole('textbox').fill('界面验收标记：仅验证Cretas跟进记录，来源缺口仍待业务核实。');
 const saved=page.waitForResponse(r=>r.url().includes('/alerts/')&&r.url().endsWith('/followup')&&r.request().method()==='POST');
 await dialog.getByRole('button',{name:'保存跟进',exact:true}).click();const saveResponse=await saved;assert.equal(saveResponse.status(),200);assert.equal((await saveResponse.json()).data.status,'awaiting_data');
 await selected.row.getByText('待补资料',{exact:true}).waitFor();
 await page.reload();await page.getByRole('heading',{name:'成本核算概览',exact:true}).waitFor({timeout:60000});
 await nav('异常与跟进').click();const persisted=page.locator(`[data-alert-id="${selected.id}"]`);await persisted.getByText('待补资料',{exact:true}).waitFor();
 assert.equal(baseline.eligibleCostReview.eligibleProjects,16);assert.equal(baseline.costAlerts.summary.costBlocked,93);
 stage='focused-followup';await persisted.getByRole('button',{name:'追问此异常',exact:true}).click();
 const focus=page.locator('.section-insight:visible').filter({has:page.getByRole('note')});await focus.waitFor();
 const pending=page.waitForResponse(r=>r.url().endsWith('/sections/followup')&&r.request().method()==='POST');
 await focus.getByRole('textbox',{name:'向这组数据提问'}).fill('这个异常接下来优先查什么？');await focus.getByRole('button',{name:'发送',exact:true}).click();
 const answer=(await (await pending).json()).data;assert.equal(answer.alertId,selected.id);assert(answer.answer.includes('当前跟进：'));
 await focus.locator('.message--assistant').waitFor();await focus.screenshot({path:join(out,'02-alert-followup.png')});
 stage='refresh-context';const refreshed=page.waitForResponse(r=>r.url().endsWith('/blacklake/analysis')&&r.request().method()==='GET');
 await page.getByRole('button',{name:'刷新分析',exact:true}).click();await refreshed;
 await page.locator('.focus-context').waitFor({state:'detached'});
 await page.getByTestId('cost-projects').locator('.el-table__body-wrapper tbody tr').first().waitFor();
 assert.equal(await page.getByRole('note').count(),0);assert.equal(await page.locator('.followup-panel:visible').count(),0);
 stage='mobile';await page.setViewportSize({width:390,height:844});await page.evaluate(()=>scrollTo(0,0));
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false);
 const toggle=page.locator('.header-start button');await toggle.click();await page.locator('.prototype-sidebar').waitFor();
 await nav('原料到成品成本').click();await page.getByRole('heading',{name:'成本核算概览',exact:true}).waitFor();
 await page.waitForFunction(()=>document.querySelector('.prototype-sidebar').getBoundingClientRect().right<=1);
 await page.waitForFunction(()=>{const r=document.querySelector('.prototype-main').getBoundingClientRect();return Math.abs(r.left)<1&&r.right<=innerWidth+1;});
 await page.waitForFunction(()=>[...document.querySelectorAll('.overview-card')].every(e=>e.getBoundingClientRect().right<=innerWidth+1));
 await page.evaluate(()=>scrollTo(0,0));
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false);
 await page.screenshot({path:join(out,'03-overview-mobile.png'),fullPage:false});
 assert.equal(errors.length,0);assert.equal(blocked.length,0);
 Object.assign(result,{success:true,metadataWrites,progressSurvivesReload:true,focusedAlertBound:true,refreshClearsContext:true,
   plainChartVerified:true,mobileOverflow:false,brandPrimary:'#1b65a8',eligible:16,blocked:93});
}catch(e){result.error=e.message;result.stage=stage;await page.screenshot({path:join(out,'failure.png'),fullPage:false});}
finally{writeFileSync(join(out,'ui-acceptance.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));await browser.close();}
process.exitCode=result.success?0:1;
