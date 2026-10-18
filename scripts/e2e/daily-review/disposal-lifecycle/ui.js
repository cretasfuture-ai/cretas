async (page) => {
  if (new URL(page.url()).origin !== 'http://127.0.0.1:31770') throw new Error('P34 loopback only');
  const errors = [], httpErrors = [], writes = [], telemetry = [], reads = [];
  const onError = e => errors.push(e.message);
  const onResponse = r => { if (r.status() >= 400) httpErrors.push({url:r.url(),status:r.status()}); };
  const guard = async route => {
    const req = route.request(), u = new URL(req.url());
    if (req.method()==='POST' && u.pathname==='/api/mobile/FOOD_3101_001/activity-events') {
      telemetry.push({method:req.method(),path:u.pathname});
      return route.continue();
    }
    if (['POST','PUT','PATCH','DELETE'].includes(req.method()) && u.pathname.startsWith('/api/mobile/')) {
      writes.push({method:req.method(),path:u.pathname});
      return route.abort();
    }
    return route.continue();
  };
  page.on('pageerror',onError); page.on('response',onResponse);
  await page.route('**/api/mobile/**',guard);
  const capture = async name => {
    const text = await page.locator('body').innerText();
    if (!text.includes('新厂双SKU体验厂')) throw new Error('Wrong factory UI');
    const screenshot = 'C:/Users/Steve/my-prototype-logistics/.playwright-mcp/p34-'+name+'.png';
    await page.screenshot({path:screenshot,fullPage:true});
    reads.push({name,url:page.url(),text,screenshot}); return text;
  };
  try {
    const historyResponse = page.waitForResponse(r => r.url().endsWith('/disposal-records/5/vouchers') && r.request().method()==='GET');
    await page.goto('http://127.0.0.1:31770/quality/disposals?disposalId=5');
    const history = (await (await historyResponse).json()).data;
    const active = history.filter(v => v.factoryId==='FOOD_3101_001' && v.status==='POSTED' && ['DISPOSAL_RECORD','DISPOSAL_RECORD_REVISION'].includes(v.sourceBusinessType));
    if (active.length!==1) throw new Error('Expected exactly one active disposal voucher');
    const activeNumber=active[0].voucherNumber;
    const originalVoucher=history.find(v=>v.factoryId==='FOOD_3101_001' && v.sourceBusinessType==='DISPOSAL_RECORD' && v.sourceBusinessId==='5');
    const redVoucher=history.find(v=>v.id===originalVoucher?.reversalVoucherId && v.originalVoucherId===originalVoucher?.id);
    if (!originalVoucher || !redVoucher) throw new Error('Missing original/red history');
    await page.getByRole('button',{name:activeNumber,exact:true}).waitFor();
    const approval = await capture('approval');
    if (!approval.includes('0.5 盒') || !approval.includes('¥4.71') || !approval.includes('p30_factory_finance')) throw new Error('Approval evidence missing');
    await page.getByRole('button',{name:activeNumber,exact:true}).click();
    await page.getByText('报废凭证不能单独作废或冲销；请通过成本更正流程同步处理报废损失和账务。',{exact:true}).waitFor();
    const revision = await capture('active-voucher');
    if (!revision.includes('6711') || !revision.includes('1405') || !revision.includes('¥4.71')) throw new Error('Revision entries missing');
    if (await page.getByRole('button',{name:'作废',exact:true}).count()) throw new Error('Generic void still visible');
    await page.getByRole('button',{name:'查看报废审批',exact:true}).click();
    await page.getByRole('button',{name:activeNumber,exact:true}).waitFor();
    await page.getByRole('button',{name:originalVoucher.voucherNumber,exact:true}).click();
    await page.getByRole('button',{name:'查看红冲凭证',exact:true}).waitFor();
    const original = await capture('original');
    if (!original.includes('已冲销') || !original.includes('¥4.21')) throw new Error('Original status missing');
    if (await page.getByRole('button',{name:'作废',exact:true}).count()) throw new Error('Original void visible');
    await page.getByRole('button',{name:'查看红冲凭证',exact:true}).click();
    await page.getByRole('button',{name:'查看原始凭证',exact:true}).waitFor();
    const red = await capture('red');
    if (!red.includes(redVoucher.voucherNumber) || !red.includes('6711') || !red.includes('1405') || !red.includes('¥4.21')) throw new Error('Red entry missing');
    await page.getByRole('button',{name:'查看原始凭证',exact:true}).click();
    await page.getByRole('button',{name:'查看报废审批',exact:true}).waitFor();
    await page.getByRole('button',{name:'查看报废审批',exact:true}).click();
    await page.getByRole('button',{name:activeNumber,exact:true}).waitFor();
    await capture('final-approval');
    if (errors.length || httpErrors.length || writes.length) throw new Error(JSON.stringify({errors,httpErrors,writes}));
    return {verdict:'PASS',factory:'FOOD_3101_001',normalUiLoginUserId:2,activeNumber,reads,pageErrors:errors,httpErrors,businessWrites:writes.length,telemetry,finalUrl:page.url()};
  } finally {
    page.off('pageerror',onError);page.off('response',onResponse);await page.unroute('**/api/mobile/**',guard);
  }
}
