// Synthetic local test only. Same component as the standard Excel analysis page.
import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { join,resolve } from 'node:path';
import { writeFileSync,mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..');
const out=join(process.env.LOCALAPPDATA,'Cretas','SmartBIAlerts');mkdirSync(out,{recursive:true});
const script="import json,os;from pathlib import Path;from blacklake.secrets import load_local_secret;s=load_local_secret(Path(os.environ['LOCALAPPDATA'])/'Cretas/SmartBIAlerts/testenv.dpapi');print(json.dumps({k:s[k] for k in ('username','password')}))";
const credentials=JSON.parse(execFileSync('python',['-c',script],{cwd:join(root,'backend/python'),encoding:'utf8'}));
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
const page=await context.newPage();const errors=[],blocked=[];
page.on('pageerror',e=>errors.push(e.message));
await context.route('**/*',route=>{
 const req=route.request(),url=new URL(req.url());
 const path=url.pathname.replace(/^\/smartbi-api(?=\/)/,'');
 if(!['127.0.0.1','localhost'].includes(url.hostname))return route.abort();
 if(['POST','PUT','PATCH','DELETE'].includes(req.method()) && ![
   '/api/mobile/auth/unified-login','/api/smartbi/data-alerts/analyze','/api/smartbi/data-alerts/uploads/101/local-review',
 ].includes(path)){blocked.push(path);return route.abort();}
 return route.continue();
});
const result={success:false,pageErrors:errors,blockedMutationAttempts:blocked,sourceBusinessWrites:0,normalJavaUploadShellVerified:false};
let stage='login';
try{
 await page.goto('http://127.0.0.1:5179/smartbi-data-alerts.html');
 await page.getByPlaceholder('请输入测试账号').fill(credentials.username);await page.getByPlaceholder('请输入密码').fill(credentials.password);
 await page.getByRole('button',{name:'登录分析',exact:true}).click();
 await page.getByRole('heading',{name:'SmartBI 多来源异常识别',exact:true}).waitFor({timeout:60000});
 const panel=page.locator('[data-testid="shared-data-alerts"]:visible');
 stage='excel-rules';
 await panel.getByText('数字字段缺失',{exact:true}).waitFor({timeout:60000});
 await panel.getByText('数字字段格式非法',{exact:true}).waitFor();
 await panel.getByText('发现完全重复行候选',{exact:true}).waitFor();
 assert.equal(await panel.getByTestId('cost-applicability').getAttribute('data-cost-status'),'not_applicable');
 await panel.locator('.el-table__expand-icon').first().click();
 await panel.getByText(/影响字段：测量值/).waitFor();
 stage='excel-ai';await panel.getByRole('button',{name:'本地 AI 核对',exact:true}).click();
 await panel.getByTestId('shared-local-ai-state').filter({hasText:'qwen3.8:27b'}).waitFor({timeout:170000});
 await page.screenshot({path:join(out,'shared-alerts-excel.png'),fullPage:true});
 stage='api-rules';await page.getByRole('tab',{name:'接口数据检查',exact:true}).click();
 await page.getByRole('button',{name:'检查合成接口数据',exact:true}).click();
 await panel.getByText('数字字段缺失',{exact:true}).waitFor();
 await panel.getByText('发现完全重复行候选',{exact:true}).waitFor();
 stage='api-ai';await page.getByRole('button',{name:'本地 AI 核对接口数据',exact:true}).click();
 await panel.getByTestId('shared-local-ai-state').filter({hasText:'qwen3.8:27b'}).waitFor({timeout:170000});
 await page.screenshot({path:join(out,'shared-alerts-api.png'),fullPage:true});
 stage='authority';await page.getByRole('tab',{name:'成本权限边界',exact:true}).click();
 await panel.getByText('授权未确认，暂不展示分析结论',{exact:true}).waitFor({timeout:30000});
 assert.equal(await panel.locator('.el-table__body-wrapper tbody tr').count(),0);
 await page.screenshot({path:join(out,'shared-alerts-authority.png'),fullPage:true});
 await page.getByRole('tab',{name:'Excel 质量检查',exact:true}).click();
 await panel.getByText('数字字段缺失',{exact:true}).waitFor({timeout:30000});
 await page.setViewportSize({width:390,height:844});
 await panel.getByText('数字字段格式非法',{exact:true}).waitFor();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false);
 await page.screenshot({path:join(out,'shared-alerts-mobile.png'),fullPage:true});
 assert.equal(errors.length,0);assert.equal(blocked.length,0);
 result.success=true;result.excelAndApiFindingsVisible=true;result.localModel='qwen3.8:27b';result.authorityUnavailableBlocked=true;result.mobileOverflow=false;
}catch(e){result.error=e.message;result.failureStage=stage;await page.screenshot({path:join(out,'shared-alerts-failure.png'),fullPage:true});}
finally{writeFileSync(join(out,'ui-acceptance.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));await browser.close();}
process.exitCode=result.success?0:1;
