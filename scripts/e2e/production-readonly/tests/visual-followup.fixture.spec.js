'use strict';
const http = require('node:http');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { runSuiteWithPage } = require('../core/run-suite');

test.describe('visual follow-up guarded local fixtures', () => {
  let server, baseUrl, mutations, mode;
  test.beforeEach(async () => {
    mutations = 0; mode = '';
    server = http.createServer((req, res) => {
      const url = new URL(req.url, 'http://fixture');
      if (url.pathname === '/api/mobile/auth/unified-login') {
        res.setHeader('content-type','application/json');
        return res.end(JSON.stringify({ success:true,data:{username:'f006_admin',factoryId:'F006',factoryName:'F006 fixture'} }));
      }
      if (['POST','PUT','PATCH','DELETE'].includes(req.method)) mutations++;
      res.setHeader('content-type','text/html; charset=utf-8');
      if (url.pathname === '/login') return res.end(`<form><input placeholder="用户名"><input type="password" placeholder="密码"><button type="submit">登录</button></form><script>document.querySelector('form').onsubmit=async e=>{e.preventDefault();const response=await fetch('/api/mobile/auth/unified-login',{method:'POST'});const body=await response.json();localStorage.setItem('cretas_user',JSON.stringify(body.data));location.href='/dashboard';}</script>`);
      if (url.pathname === '/sales/orders/new') return res.end(`<main><h1>新建销售订单</h1><p>客户与订单信息；本地日期夹具不允许保存，也不允许真实业务请求。</p><div class="order-delivery-date"><input aria-label="交货日期" aria-invalid="false"><button class="calendar-picker">日历</button></div><div role="alert" hidden>日期无效，请输入 YYYYMMDD 或 YYYY-MM-DD</div><div class="el-picker-panel" hidden><table><tr><td class="available">15</td></tr></table></div><button id="cancel">取消</button><button id="leave" hidden>离开</button><button id="save">保存草稿</button></main><script>
      const input=document.querySelector('input'), error=document.querySelector('[role=alert]');
      input.onchange=()=>{const bad=input.value==='20260229';error.hidden=!bad;input.setAttribute('aria-invalid',String(bad));if(input.value==='20300115')input.value='2030-01-15';${mode==='date-autosave' ? "fetch('/api/mobile/F006/sales/orders',{method:'POST'}).catch(()=>{});" : ''}};
      document.querySelector('.calendar-picker').onclick=()=>document.querySelector('.el-picker-panel').hidden=false;
      document.querySelector('td').onclick=()=>{input.value='2030-01-15';input.setAttribute('aria-invalid','false');error.hidden=true;document.querySelector('.el-picker-panel').hidden=true;};
      document.querySelector('#cancel').onclick=()=>setTimeout(()=>document.querySelector('#leave').hidden=false,200);
      document.querySelector('#leave').onclick=()=>location.href='/sales/orders';
      document.querySelector('#save').onclick=()=>fetch('/api/mobile/F006/sales/orders',{method:'POST'});
      </script>`);
      if (url.pathname === '/production/process-hub') return res.end(`<style>body{margin:0}main{min-height:100vh}.workflow-editor{position:fixed;inset:0}.workflow-ai-dock{height:100px}.workflow-editor.embedded{position:relative;height:500px}.el-tabs__header{display:none}</style><main class="app-content"><div class="process-config-hub"><div class="hub-tabs"><div class="el-tabs__header">产品-工序配置</div></div><div class="workflow-editor"><p>Workflow 本地状态模拟，保留三个节点，适应画布会安排自动保存因此禁止点击。</p><button data-testid="workflow-immersive-exit">退出全屏</button><button data-testid="workflow-immersive-enter" hidden>全屏</button><button id="fit">适应画布</button><div class="vue-flow__node" data-id="a">原料</div><div class="vue-flow__node" data-id="b">工序</div><div class="vue-flow__node" data-id="c">成品</div><div class="workflow-ai-dock"><button>收起</button></div></div></div></main><script>
      const editor=document.querySelector('.workflow-editor'), tabs=document.querySelector('.el-tabs__header'), enter=document.querySelector('[data-testid=workflow-immersive-enter]'), exit=document.querySelector('[data-testid=workflow-immersive-exit]');
      const autosave=()=>setTimeout(()=>fetch('/api/mobile/F006/product-process-workflows/PT',{method:'PUT'}).catch(()=>{}),2500);
      document.querySelector('#fit').onclick=autosave;
      exit.onclick=()=>{editor.classList.add('embedded');tabs.style.display='block';enter.hidden=false;exit.hidden=true;${mode==='exit-autosave'?'autosave();':''}};
      enter.onclick=()=>{editor.classList.remove('embedded');tabs.style.display='none';enter.hidden=true;exit.hidden=false;};
      document.querySelector('.workflow-ai-dock button').onclick=()=>document.querySelector('.workflow-ai-dock').style.height='20px';
      </script>`);
      if (mode === 'wrong-route' && url.pathname === '/quality/label-qc') { res.writeHead(302,{location:'/dashboard'}); return res.end(); }
      if (url.pathname === '/quality/label-qc') return res.end(`<main><h1>包装标签拍检</h1><p>本地审核队列仅切换显示，绝不执行实际人工审核动作。</p><div class="queue-toolbar"><h2>审核队列</h2></div><label class="el-radio-button"><input type="radio" name="queue">已完成记录</label><label class="el-radio-button"><input type="radio" name="queue" checked>待人工审核</label></main><script>document.querySelectorAll('.el-radio-button').forEach((el,i)=>el.onclick=e=>{${mode==='queue-noop' ? 'e.preventDefault();' : "document.querySelector('.queue-toolbar h2').textContent=i===0?'已完成记录':'审核队列';"}});</script>`);
      if (url.pathname === '/production/kanban' || url.pathname === '/production/analysis-query') {
        const selected=mode==='wrong-tab'?'wrong':url.searchParams.get('tab');
        const names=url.pathname.endsWith('kanban')?['pending-queue','restock-board']:['bom-achievement','process-io'];
        const labels={'pending-queue':'未完成计划队列','restock-board':'备货看板','bom-achievement':'BOM达成率分析','process-io':'工序投入产出对比'};
        return res.end(`<main><h1>本地业务查询</h1><p>用于验证真实目标与标签，不依赖侧栏关键词。</p><div class="hub-tabs">${names.map(n=>`<button role="tab" id="tab-${n}" aria-selected="${selected===n}">${labels[n]}</button><section id="pane-${n}" ${selected===n?'':'hidden'}><h2>${labels[n]}</h2></section>`).join('')}</div></main><script>document.querySelectorAll('[role=tab]').forEach(t=>t.onclick=()=>{const tab=t.id.slice(4);history.replaceState({},'',location.pathname+'?tab='+tab);document.querySelectorAll('[role=tab]').forEach(x=>x.setAttribute('aria-selected',String(x===t)));document.querySelectorAll('section').forEach(x=>x.hidden=x.id!=='pane-'+tab);});</script>`);
      }
      res.end('<main><h1>快捷操作</h1><p>F006 fixture，当前租户的本地工作台页面，仅供只读回归测试。</p></main>');
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    baseUrl=`http://127.0.0.1:${server.address().port}`;
  });
  test.afterEach(async()=>{server.closeAllConnections?.();await new Promise(resolve=>server.close(resolve));});
  const run = (page, scenarios, suffix) => runSuiteWithPage(page,{baseUrl,username:'f006_admin',password:'fixture-only',scenarios,evidenceDir:path.resolve('test-results',`followup-${suffix}`)});
  test('valid invalid calendar and discard never save', async({page})=>{
    const report=await run(page,['sales-date-draft-readonly'],'date');
    expect(report.scenarios[0].result).toBe('PASS');expect(report.scenarios[0].screenshots).toHaveLength(4);
    expect(report.actualBusinessWrites).toBe(0);expect(report.blockedMutationAttempts).toHaveLength(0);expect(mutations).toBe(0);
  });
  test('fit autosave is deliberately untested while safe viewport controls retain nodes',async({page})=>{
    const report=await run(page,['workflow-viewport-readonly'],'viewport');
    expect(report.scenarios[0].result).toBe('UNVERIFIED');expect(report.scenarios[0].screenshots).toHaveLength(4);
    expect(report.blockedMutationAttempts).toHaveLength(0);expect(mutations).toBe(0);
  });
  test('delayed viewport autosave stops before reentry or another scenario',async({page})=>{
    mode='exit-autosave'; const report=await run(page,['workflow-viewport-readonly','sales-date-draft-readonly'],'viewport-block');
    expect(report.blockedMutationAttempts).toHaveLength(1);expect(report.scenarios).toHaveLength(1);await expect(page.getByTestId('workflow-immersive-enter')).toBeVisible();expect(mutations).toBe(0);
  });
  test('unexpected date autosave stops the suite before later scenarios',async({page})=>{
    mode='date-autosave';const report=await run(page,['sales-date-draft-readonly','workflow-viewport-readonly'],'date-block');
    expect(report.blockedMutationAttempts).toHaveLength(1);expect(report.scenarios).toHaveLength(1);expect(mutations).toBe(0);
  });
  test('bounded followup switches real selected panes',async({page})=>{
    const report=await run(page,['visual-unverified-followup-readonly'],'panes');expect(report.scenarios[0].result).toBe('PASS');expect(report.blockedMutationAttempts).toHaveLength(0);
  });
  for(const fault of ['wrong-route','wrong-tab','queue-noop'])test(`rejects ${fault}`,async({page})=>{
    test.setTimeout(60000);mode=fault;const report=await run(page,['visual-unverified-followup-readonly'],fault);expect(report.scenarios[0].result).toBe('UNVERIFIED');expect(mutations).toBe(0);
  });
});
