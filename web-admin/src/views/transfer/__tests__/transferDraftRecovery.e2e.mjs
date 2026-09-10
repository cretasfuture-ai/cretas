import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const baseUrl=process.env.BASE_URL||'http://127.0.0.1:5187';
assert.equal(new URL(baseUrl).hostname,'127.0.0.1');
const out=resolve('test-results/b07-transfer-recovery'); await mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,channel:'chrome'});
const results=[];
try {
  for(const scenario of ['tab-recovery','local-retry','valid-empty','tenant-during-retry']) {
    const context=await browser.newContext({viewport:{width:1440,height:1000}});
    await context.addInitScript(()=>localStorage.setItem('cretas_user',JSON.stringify({id:1,username:'b07-recovery',userType:'factory',factoryUser:{factoryId:'LOCAL_A',role:'factory_super_admin',factoryType:'FACTORY'}})));
    let mode=scenario==='valid-empty'?'empty':'fail'; let release;
    const requests=[], errors=[];
    await context.route('**/*',async route=>{
      const u=new URL(route.request().url());
      if(u.origin!==new URL(baseUrl).origin)return route.abort();
      if(!u.pathname.startsWith('/api/'))return route.continue();
      requests.push({path:u.pathname,method:route.request().method(),mode});
      let data=[];
      if(u.pathname.endsWith('/transfers'))data={content:[],totalElements:0};
      if(u.pathname.includes('/api/admin/role-permissions'))data=['inventory','warehouse'].map(moduleCode=>({roleCode:'factory_super_admin',moduleCode,permissionLevel:'rw'}));
      if(u.pathname.includes('role-module-override'))data={};
      if(u.pathname.endsWith('/factories/network'))data=[{factoryId:'LOCAL_A',factoryName:'本地A'},{factoryId:'LOCAL_B',factoryName:'本地B'}];
      if(u.pathname.endsWith('/factory/warehouses')){
        if(u.pathname.includes('/LOCAL_B/'))data=[{id:'B',name:'新租户仓库'}];
        else if(mode==='fail')return route.fulfill({status:500,json:{success:false,message:'B07 临时仓库故障'}});
        else if(mode==='delay'){await new Promise(resolve=>{release=resolve;});data=[{id:'OLD',name:'旧租户延迟仓库'}];}
        else data=mode==='empty'?[]:[{id:'RECOVERED',name:'恢复后的仓库'}];
      }
      await route.fulfill({json:{success:true,data}});
    });
    const page=await context.newPage(); page.on('pageerror',e=>errors.push(e.message));
    const count=(factory='LOCAL_A')=>requests.filter(r=>r.path===`/api/mobile/${factory}/factory/warehouses`).length;
    const networkCount=()=>requests.filter(r=>r.path.endsWith('/factories/network')).length;
    const remark=page.getByPlaceholder('如: 领用 / 研发样品 / 余料退总仓 等');
    const retry=page.getByRole('button',{name:'重试加载选项',exact:true});
    const source=page.locator('.el-form-item').filter({has:page.locator('.el-form-item__label',{hasText:'调出仓库'})}).locator('.el-select');
    try {
      await page.goto(`${baseUrl}/transfer/list`);
      await page.getByRole('button',{name:'手动新建调拨单',exact:true}).click();
      await expect.poll(()=>count()).toBe(1); await remark.fill('恢复时保留输入');
      if(scenario!=='valid-empty')await expect(retry).toBeVisible();
      await page.screenshot({path:resolve(out,`${scenario}-before.png`),fullPage:true});
      const before={warehouse:count(),network:networkCount()};
      if(scenario==='tab-recovery'||scenario==='valid-empty'){
        if(scenario==='tab-recovery')mode='success';
        for(let i=0;i<3;i++){
          await page.locator('.workspace-tab').nth(0).click(); await page.locator('.workspace-tab').nth(1).click();
          await expect(remark).toHaveValue('恢复时保留输入');
          await expect.poll(()=>count()).toBe(scenario==='tab-recovery'?2:1);
        }
      } else if(scenario==='local-retry'){
        mode='success'; await retry.click(); await expect.poll(()=>count()).toBe(2); await expect(remark).toHaveValue('恢复时保留输入');
      } else {
        mode='delay'; await retry.click(); await expect.poll(()=>typeof release).toBe('function');
        await page.evaluate(async()=>{const {useAuthStore}=await import('/src/store/modules/auth.ts');useAuthStore().user.factoryUser.factoryId='LOCAL_B';});
        await expect.poll(()=>count('LOCAL_B')).toBe(1); await expect(remark).toHaveValue('');
        release(); await page.waitForTimeout(150);
      }
      await expect(retry).toHaveCount(0);
      const listId=await source.getByRole('combobox').getAttribute('aria-controls'); await source.click();
      const options=page.locator(`[id="${listId}"] .el-select-dropdown__item`);
      if(scenario==='valid-empty')await expect(options).toHaveCount(0);
      else {
        const expected=scenario==='tenant-during-retry'?'新租户仓库':'恢复后的仓库';
        await expect(options).toHaveText([expected]); await options.first().click();
      }
      if(scenario!=='tenant-during-retry')assert.equal(networkCount(),before.network);
      assert.deepEqual(errors,[]);
      const after={warehouse:count(),network:networkCount(),newTenantWarehouse:count('LOCAL_B')};
      results.push({scenario,pass:true,before,after,remark:await remark.inputValue(),requests,errors});
      await page.screenshot({path:resolve(out,`${scenario}-after.png`),fullPage:true});
    } catch(error) {
      await page.screenshot({path:resolve(out,`${scenario}-failure.png`),fullPage:true});
      await writeFile(resolve(out,`${scenario}-failure.json`),JSON.stringify({error:String(error),requests,errors},null,2));throw error;
    } finally {await context.close();}
  }
  await writeFile(resolve(out,'result.json'),JSON.stringify({pass:true,realBusinessWrites:0,results},null,2));
} finally {await browser.close();}
