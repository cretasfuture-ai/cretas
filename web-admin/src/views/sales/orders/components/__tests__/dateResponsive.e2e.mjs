import { chromium, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
const out=resolve('.playwright-mcp/date-responsive');mkdirSync(out,{recursive:true});
const browser=await chromium.launch({headless:true}); const results=[];
for(const phase of ['before','after']) for(const width of [1024,1280,1440]){
 const port=phase==='before'?5192:5193;
 const context=await browser.newContext({viewport:{width,height:900}});
 await context.addInitScript(()=>localStorage.setItem('cretas_user',JSON.stringify({id:1,username:'date-review',userType:'factory',factoryUser:{factoryId:'LOCAL',role:'factory_super_admin',factoryType:'FACTORY'}})));
 const requests=[];const errors=[];
 const order={id:'SO-DATE',orderNumber:'SO-DATE-0001',status:'DRAFT',customerId:'C',customerName:'测试客户',requiredDeliveryDate:'2026-08-20',items:[]};
 await context.route('**/*',async route=>{
  const u=new URL(route.request().url());if(u.origin!==`http://127.0.0.1:${port}`)return route.abort();if(!u.pathname.startsWith('/api/'))return route.continue();requests.push({path:u.pathname,method:route.request().method()});
  let data=[];
  if(u.pathname.includes('/api/admin/role-permissions'))data=['sales','warehouse','inventory'].map(moduleCode=>({roleCode:'factory_super_admin',moduleCode,permissionLevel:'rw'}));
  if(u.pathname.includes('role-module-override'))data={};
  if(u.pathname.endsWith('/factory/warehouses'))data=[{id:'W1',code:'W1',name:'成品仓',type:'FINISHED',isActive:true}];
  if(u.pathname.endsWith('/customers'))data={content:[{id:'C',name:'测试客户'}],totalElements:1};
  if(u.pathname.endsWith('/sales/orders'))data={content:[order],totalElements:1};
  if(u.pathname.endsWith('/sales/orders/SO-DATE'))data=order;
  await route.fulfill({json:{success:true,data}});
 });
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.goto(`http://127.0.0.1:${port}/sales/orders/new`);await page.getByRole('button',{name:'保存草稿',exact:true}).waitFor();await page.waitForTimeout(500);
  const date=page.getByRole('textbox',{name:'交货日期',exact:true});
  const geometry=await page.evaluate(()=>{const date=document.querySelector('input[aria-label="交货日期"]');const sidebar=document.querySelector('.app-sidebar');return {input:date.getBoundingClientRect().toJSON(),sidebarWidth:sidebar?.getBoundingClientRect().width,bodyOverflow:document.documentElement.scrollWidth>innerWidth};});
  await page.screenshot({path:resolve(out,`${phase}-${width}.png`),fullPage:true});
  const row={phase,width,geometry,visible:await date.isVisible(),requests,errors};results.push(row);
  if(phase==='after'){
   expect(geometry.bodyOverflow).toBe(false);expect(geometry.input.width).toBeGreaterThan(100);await expect(date).toBeVisible();
   await date.fill('20260820');await date.press('Tab');await expect(date).toHaveValue('2026-08-20');
   await date.fill('20260229');await date.press('Tab');await expect(date).toHaveAttribute('aria-invalid','true');await expect(page.getByRole('alert').filter({hasText:'日期无效'})).toBeVisible();
   const writes=requests.filter(r=>r.method==='POST'&&r.path.endsWith('/sales/orders')).length;
   await page.getByRole('button',{name:'保存草稿',exact:true}).click();expect(requests.filter(r=>r.method==='POST'&&r.path.endsWith('/sales/orders')).length).toBe(writes);
   await page.screenshot({path:resolve(out,`after-${width}-error.png`),fullPage:true});
   await page.locator('.calendar-picker').click();await page.locator('.el-date-picker:visible td.available').filter({hasText:/^15$/}).first().click();await expect(date).toHaveAttribute('aria-invalid','false');
   row.interactions='typed valid, invalid shown, invalid submit blocked, calendar restored valid';
   await page.goto(`http://127.0.0.1:${port}/sales/orders`);await page.getByText('SO-DATE-0001',{exact:true}).waitFor();
   const edit=page.getByRole('button',{name:/继续编辑|编辑/}).first();
   if(await edit.count())await edit.click();else{await page.getByRole('button',{name:/更多/}).first().click();await page.getByText('编辑订单',{exact:true}).last().click();}
   await page.getByRole('dialog').waitFor();const editDate=page.getByRole('dialog').getByRole('textbox',{name:'交货日期',exact:true});await expect(editDate).toBeVisible();await editDate.fill('20260820');await editDate.press('Tab');await expect(editDate).toHaveValue('2026-08-20');
   await page.keyboard.press('Escape');await page.waitForTimeout(700);
   await page.screenshot({path:resolve(out,`after-${width}-edit.png`),fullPage:true});row.edit='visible and typed date normalized';
  }
 }catch(e){results.push({phase,width,error:String(e)});await page.screenshot({path:resolve(out,`${phase}-${width}-failure.png`),fullPage:true});throw e;}
 finally{writeFileSync(resolve(out,'result.json'),JSON.stringify(results,null,2));await context.close();}
}
await browser.close();
