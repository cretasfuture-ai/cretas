#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { performUiLogin } = require('../production-readonly/core/ui-login.js');

const baseUrl = process.env.E2E_ADMIN_URL || 'https://admin.cretaceousfuture.com';
const password = process.env.E2E_PASSWORD;
const runKey = process.env.E2E_RUN_KEY;
const phase = process.argv[2] || 'procurement-create-submit';
if (!password || !runKey) throw new Error('E2E_PASSWORD and E2E_RUN_KEY are required');
if (!/^E2E-RBAC-20260823-[A-Z0-9-]+$/.test(runKey)) throw new Error(`Unsafe run key: ${runKey}`);

const evidenceDir = path.resolve(process.env.E2E_EVIDENCE_DIR || path.join('.playwright-mcp', 'f006-role-chain', runKey));
const statePath = path.join(evidenceDir, 'state.json');
const reportPath = path.join(evidenceDir, `${phase}.json`);
fs.mkdirSync(evidenceDir, { recursive: true });

function loadState() {
  if (!fs.existsSync(statePath)) return { runKey, factoryId: 'F006', entities: {}, mutations: [] };
  const value = JSON.parse(fs.readFileSync(statePath, 'utf8'));
  if (value.runKey !== runKey || value.factoryId !== 'F006') throw new Error('State tenant/run key mismatch');
  return value;
}

function persistState(state) {
  fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
}

function compactBusinessData(body) {
  const data = body?.data || {};
  return {
    success: body?.success ?? null,
    message: body?.message || null,
    id: data.id || null,
    factoryId: data.factoryId || null,
    orderNumber: data.orderNumber || null,
    status: data.status || null,
    totalAmount: data.totalAmount ?? null,
    version: data.version ?? null,
  };
}

function compactPayableData(body) {
  const data = body?.data || {};
  return {
    success: body?.success ?? null,
    message: body?.message || null,
    payableTransactionId: data.payableTransactionId || data.id || null,
    paymentTransactionId: data.paymentTransactionId || null,
    paymentTransactionNumber: data.paymentTransactionNumber || data.transactionNumber || null,
    allocationId: data.allocationId || null,
    supplierId: data.supplierId || null,
    allocatedAmount: data.allocatedAmount ?? null,
    settledAmount: data.settledAmount ?? null,
    outstandingAmount: data.outstandingAmount ?? null,
    currencyCode: data.currencyCode || null,
    paymentStatus: data.paymentStatus || null,
    replayed: data.replayed ?? null,
  };
}

async function loginRole(page, username, expectedRole) {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  return performUiLogin(page, {
    username,
    password,
    expectedUsername: username,
    expectedFactoryId: 'F006',
    expectedRole,
  });
}

async function authenticatedGet(page, pathname) {
  return page.evaluate(async ({ url }) => {
    const token = localStorage.getItem('cretas_access_token');
    if (!token) throw new Error('Authenticated token missing from browser storage');
    const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
    const body = await response.json().catch(() => null);
    return { status: response.status, body };
  }, { url: `${baseUrl}${pathname}` });
}

async function waitForAutomaticInvoice(page, salesOrderId, timeoutMs = 35_000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    last = await authenticatedGet(page,
      `/api/mobile/F006/finance/invoices/by-sales-order/${encodeURIComponent(salesOrderId)}`);
    const rows = Array.isArray(last.body?.data) ? last.body.data : [];
    const requested = rows.filter((row) => row?.salesOrderId === salesOrderId && row?.status === 'REQUESTED');
    if (last.status < 400 && last.body?.success === true && requested.length === 1) {
      return { response: last, invoice: requested[0], all: rows };
    }
    if (requested.length > 1) {
      throw new Error(`Automatic invoice is not idempotent for ${salesOrderId}: ${requested.length} REQUESTED records`);
    }
    await page.waitForTimeout(1_000);
  }
  throw new Error(`Automatic invoice did not appear after completed delivery: ${JSON.stringify(last)}`);
}

async function selectOption(page, selectRoot, label) {
  await selectRoot.click();
  const option = page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: label }).first();
  await option.waitFor({ state: 'visible', timeout: 15_000 });
  await option.click();
}

async function procurementCreateSubmit(page, state, report) {
  if (state.entities.purchaseOrder) {
    if (process.env.E2E_REPLACE_PURCHASE_ORDER !== '1') {
      throw new Error(`Purchase order already tracked: ${state.entities.purchaseOrder.id}; refusing duplicate creation`);
    }
    state.entities.supersededPurchaseOrders = [
      ...(state.entities.supersededPurchaseOrders || []),
      state.entities.purchaseOrder,
    ];
    delete state.entities.purchaseOrder;
    persistState(state);
  }
  const username = 'f006_procurement_mgr';
  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  const login = await performUiLogin(page, {
    username,
    password,
    expectedUsername: username,
    expectedFactoryId: 'F006',
    expectedRole: 'procurement_manager',
  });
  report.login = login;
  if (login.factoryId !== 'F006') throw new Error('F006 proof failed before mutation');

  const intended = {
    step: '采购下单并提交',
    method: 'POST',
    endpoint: '/api/mobile/F006/purchase/orders',
    target: { supplier: '北京飞熊', material: '2015胸肉' },
    payloadBoundary: { quantity: 1, unitPrice: 1.23, taxRate: 0.13, settlementType: 'PREPAID', remark: runKey },
  };
  report.intendedMutations.push(intended);

  await page.goto(`${baseUrl}/procurement/orders`, { waitUntil: 'domcontentloaded' });
  const dropdownRefresh = Promise.all([
    page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/mobile/F006/suppliers')
      && new URL(response.url()).searchParams.get('size') === '100'),
    page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/mobile/F006/raw-material-types/active')),
    page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/mobile/F006/sales/orders')
      && new URL(response.url()).searchParams.get('size') === '100'),
  ]);
  await page.getByRole('button', { name: /新建采购订单/ }).click();
  const dialog = page.locator('.el-dialog').filter({ hasText: '新建采购订单' });
  await dialog.waitFor({ state: 'visible', timeout: 15_000 });
  const dropdownResponses = await dropdownRefresh;
  report.dropdownRefresh = dropdownResponses.map((response) => ({ url: response.url(), status: response.status() }));
  await dialog.locator('.el-select.is-loading').first().waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {});

  const supplierItem = dialog.locator('.el-form-item').filter({ hasText: '供应商' }).first();
  await selectOption(page, supplierItem.locator('.el-select'), '北京飞熊');
  const settlementItem = dialog.locator('.el-form-item').filter({ hasText: '结算方式' }).first();
  await selectOption(page, settlementItem.locator('.el-select'), '预付');

  const itemRow = dialog.locator('.item-row:not(.item-header)').first();
  await itemRow.waitFor({ state: 'visible', timeout: 15_000 });
  await selectOption(page, itemRow.locator('.el-select').first(), '2015胸肉');
  await itemRow.locator('.el-input-number input').nth(0).fill('1');
  await itemRow.locator('.el-input-number input').nth(1).fill('1.23');
  await selectOption(page, itemRow.locator('.tax-select-cell .el-select'), '13%');
  await dialog.locator('.el-form-item').filter({ hasText: '备注' }).locator('textarea').fill(runKey);

  const createResponsePromise = page.waitForResponse((response) => {
    const request = response.request();
    return request.method() === 'POST' && /\/api\/mobile\/F006\/purchase\/orders$/.test(new URL(response.url()).pathname);
  }, { timeout: 20_000 });
  await dialog.getByRole('button', { name: '创建', exact: true }).click();
  const createResponse = await createResponsePromise;
  const createBody = await createResponse.json();
  const created = compactBusinessData(createBody);
  report.actualMutations.push({ method: 'POST', url: createResponse.url(), statusCode: createResponse.status(), response: created });
  if (createResponse.status() >= 400 || !created.success || !created.id) throw new Error(`Purchase create failed: ${createResponse.status()} ${created.message}`);
  if (created.factoryId && created.factoryId !== 'F006') throw new Error(`Created PO tenant mismatch: ${created.factoryId}`);
  state.entities.purchaseOrder = created;
  state.mutations.push({ phase, action: 'create-purchase-order', entityId: created.id, at: new Date().toISOString() });
  persistState(state);

  await dialog.waitFor({ state: 'hidden', timeout: 20_000 });
  const search = page.locator('input[placeholder*="订单号 / 供应商"]').first();
  await search.fill(created.orderNumber);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  const row = page.locator('.el-table__row').filter({ hasText: created.orderNumber }).first();
  await row.waitFor({ state: 'visible', timeout: 15_000 });

  const submitResponsePromise = page.waitForResponse((response) => {
    const request = response.request();
    return request.method() === 'POST'
      && new URL(response.url()).pathname.endsWith(`/api/mobile/F006/purchase/orders/${created.id}/submit`);
  }, { timeout: 20_000 });
  await row.getByRole('button', { name: '提交', exact: true }).click();
  const confirm = page.locator('.el-message-box').filter({ hasText: created.orderNumber });
  await confirm.getByRole('button', { name: '确认提交', exact: true }).click();
  const submitResponse = await submitResponsePromise;
  const submitBody = await submitResponse.json();
  const submitted = compactBusinessData(submitBody);
  report.actualMutations.push({ method: 'POST', url: submitResponse.url(), statusCode: submitResponse.status(), response: submitted });
  if (submitResponse.status() >= 400 || !submitted.success) throw new Error(`Purchase submit failed: ${submitResponse.status()} ${submitted.message}`);
  state.entities.purchaseOrder = { ...created, ...submitted };
  state.mutations.push({ phase, action: 'submit-purchase-order', entityId: created.id, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '01-procurement-submitted.png'), fullPage: true });
}

async function warehouseReceive(page, state, report) {
  const purchaseOrder = state.entities.purchaseOrder;
  if (!purchaseOrder?.id || !purchaseOrder?.orderNumber) throw new Error('Tracked purchase order is required');
  if (state.entities.purchaseReceipt) throw new Error(`Purchase receipt already tracked: ${state.entities.purchaseReceipt.id}`);
  report.login = await loginRole(page, 'f006_warehouse_mgr', 'warehouse_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before warehouse receipt mutation');
  report.intendedMutations.push({
    step: '仓储确认采购收货并生成库存批次及未清应付',
    create: { method: 'POST', endpoint: '/api/mobile/F006/warehouse/receiving/receipts' },
    confirm: { method: 'POST', endpoint: '/api/mobile/F006/warehouse/receiving/receipts/{id}/confirm' },
    target: { purchaseOrderId: purchaseOrder.id, purchaseOrderNumber: purchaseOrder.orderNumber },
    payloadBoundary: { receivedQuantity: 1, remark: runKey, attachment: '01-procurement-submitted.png' },
  });

  const taskResponsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname.endsWith('/api/mobile/F006/warehouse/receiving/tasks'));
  await page.goto(`${baseUrl}/warehouse/materials?view=receiving&purchaseOrderId=${encodeURIComponent(purchaseOrder.id)}`, {
    waitUntil: 'domcontentloaded',
  });
  const taskResponse = await taskResponsePromise;
  const taskBody = await taskResponse.json();
  const tasks = Array.isArray(taskBody?.data) ? taskBody.data : [];
  const task = tasks.find((item) => item.purchaseOrderId === purchaseOrder.id);
  report.receivingTask = task ? {
    taskId: task.taskId,
    purchaseOrderId: task.purchaseOrderId,
    orderNumber: task.orderNumber,
    sourceType: task.sourceType,
    status: task.status,
    factoryId: task.factoryId || 'F006',
  } : null;
  if (!task) throw new Error(`Warehouse receiving task not found for ${purchaseOrder.orderNumber}`);

  const row = page.locator('.el-table__row').filter({ hasText: purchaseOrder.orderNumber }).first();
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  await row.getByRole('button', { name: /收货/, exact: true }).click();
  const dialog = page.locator('.el-dialog').filter({ hasText: `采购收货 — ${purchaseOrder.orderNumber}` });
  await dialog.waitFor({ state: 'visible', timeout: 20_000 });
  await dialog.locator('.el-form-item').filter({ hasText: '备注' }).locator('textarea').fill(runKey);
  const attachmentPath = path.join(evidenceDir, '01-procurement-submitted.png');
  if (!fs.existsSync(attachmentPath)) throw new Error(`Receipt evidence file missing: ${attachmentPath}`);
  await dialog.locator('input[type="file"]').first().setInputFiles(attachmentPath);
  await dialog.getByText(/01-procurement-submitted\.png/).waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {});

  const createResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/api/mobile/F006/warehouse/receiving/receipts'));
  await dialog.getByRole('button', { name: '确认收货入库', exact: true }).click();
  const createResponse = await createResponsePromise;
  const createBody = await createResponse.json();
  const receiptData = createBody?.data || {};
  const created = {
    success: createBody?.success ?? null,
    message: createBody?.message || null,
    id: receiptData.id || null,
    factoryId: receiptData.factoryId || null,
    receiveNumber: receiptData.receiveNumber || null,
    purchaseOrderId: receiptData.purchaseOrderId || null,
    totalAmount: receiptData.totalAmount ?? null,
    status: receiptData.status || null,
  };
  report.actualMutations.push({ method: 'POST', url: createResponse.url(), statusCode: createResponse.status(), response: created });
  if (createResponse.status() >= 400 || !created.success || !created.id || !created.receiveNumber) {
    throw new Error(`Receipt create failed: ${createResponse.status()} ${created.message}`);
  }
  if (created.factoryId && created.factoryId !== 'F006') throw new Error(`Receipt tenant mismatch: ${created.factoryId}`);
  if (created.purchaseOrderId !== purchaseOrder.id) throw new Error('Receipt PO mismatch');
  state.entities.purchaseReceipt = created;
  state.mutations.push({ phase, action: 'create-purchase-receipt', entityId: created.id, at: new Date().toISOString() });
  persistState(state);

  const confirmResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/api/mobile/F006/warehouse/receiving/receipts/${created.id}/confirm`));
  const confirm = page.locator('.el-message-box').filter({ hasText: created.receiveNumber });
  await confirm.getByRole('button', { name: '确认收货入库', exact: true }).click();
  const confirmResponse = await confirmResponsePromise;
  const confirmBody = await confirmResponse.json();
  const confirmedData = confirmBody?.data || {};
  const confirmed = {
    success: confirmBody?.success ?? null,
    message: confirmBody?.message || null,
    id: confirmedData.id || null,
    factoryId: confirmedData.factoryId || null,
    receiveNumber: confirmedData.receiveNumber || null,
    purchaseOrderId: confirmedData.purchaseOrderId || null,
    totalAmount: confirmedData.totalAmount ?? null,
    status: confirmedData.status || null,
    materialBatchIds: (confirmedData.items || []).map((item) => item.materialBatchId).filter(Boolean),
  };
  report.actualMutations.push({ method: 'POST', url: confirmResponse.url(), statusCode: confirmResponse.status(), response: confirmed });
  if (confirmResponse.status() >= 400 || !confirmed.success || confirmed.status !== 'CONFIRMED') {
    throw new Error(`Receipt confirm failed: ${confirmResponse.status()} ${confirmed.message} status=${confirmed.status}`);
  }
  state.entities.purchaseReceipt = { ...created, ...confirmed };
  state.mutations.push({ phase, action: 'confirm-purchase-receipt', entityId: created.id, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '02-warehouse-received.png'), fullPage: true });
}

async function financeSettlePayable(page, state, report) {
  const purchaseOrder = state.entities.purchaseOrder;
  const purchaseReceipt = state.entities.purchaseReceipt;
  if (!purchaseOrder?.id || !purchaseReceipt?.id) throw new Error('Tracked purchase order and confirmed receipt are required');
  if (state.entities.payableSettlement) throw new Error(`Payable settlement already tracked: ${state.entities.payableSettlement.paymentTransactionId}`);
  report.login = await loginRole(page, 'f006_finance_mgr', 'finance_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before payable settlement mutation');
  report.intendedMutations.push({
    step: '财务主管从本次收货生成的未清应付确认付款并核销',
    method: 'POST',
    endpoint: '/api/mobile/F006/finance/payables/{payableTransactionId}/settlements',
    target: { purchaseOrderId: purchaseOrder.id, purchaseReceiptId: purchaseReceipt.id },
    payloadBoundary: { paymentMethod: 'BANK_TRANSFER', paymentReference: runKey },
  });

  await page.goto(`${baseUrl}/finance/ar-ap`, { waitUntil: 'domcontentloaded' });
  const transactionsResponsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname.endsWith('/api/mobile/F006/finance/transactions')
    && new URL(response.url()).searchParams.get('counterpartyType') === 'SUPPLIER');
  await page.getByText('应付账款', { exact: true }).click();
  const transactionsResponse = await transactionsResponsePromise;
  const transactionsBody = await transactionsResponse.json();
  const transactions = transactionsBody?.data?.content || [];
  const payable = transactions.find((item) => item.transactionType === 'AP_INVOICE'
    && item.purchaseOrderId === purchaseOrder.id
    && Number(item.outstandingAmount) > 0);
  report.openPayable = payable ? {
    id: payable.id,
    factoryId: payable.factoryId,
    transactionNumber: payable.transactionNumber,
    purchaseOrderId: payable.purchaseOrderId,
    supplierId: payable.counterpartyId,
    amount: payable.amount,
    outstandingAmount: payable.outstandingAmount,
    paymentStatus: payable.paymentStatus,
  } : null;
  if (!payable?.id || payable.factoryId !== 'F006') throw new Error(`Open F006 payable not found for PO ${purchaseOrder.orderNumber}`);
  state.entities.payable = report.openPayable;
  persistState(state);

  const row = page.locator('.el-table__row:visible').filter({ hasText: payable.transactionNumber }).first();
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  await row.getByRole('button', { name: '付款并核销', exact: true }).click();
  const dialog = page.locator('.el-dialog').filter({ hasText: '付款并核销未清应付' });
  await dialog.waitFor({ state: 'visible' });
  await dialog.locator('input[placeholder="银行流水号或付款凭证号"]').fill(runKey);
  await dialog.locator('textarea').fill(`${runKey} 财务付款核销`);
  const settlementResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/api/mobile/F006/finance/payables/${payable.id}/settlements`));
  await dialog.getByRole('button', { name: '确认付款', exact: true }).click();
  const confirmation = page.locator('.el-message-box').filter({ hasText: '确认付款核销' });
  await confirmation.getByRole('button', { name: '确认付款', exact: true }).click();
  const settlementResponse = await settlementResponsePromise;
  const settled = compactPayableData(await settlementResponse.json());
  report.actualMutations.push({ method: 'POST', url: settlementResponse.url(), statusCode: settlementResponse.status(), response: settled });
  if (settlementResponse.status() >= 400 || !settled.success || settled.payableTransactionId !== payable.id
      || Number(settled.outstandingAmount) !== 0 || settled.paymentStatus !== 'PAID') {
    throw new Error(`Payable settlement failed: ${settlementResponse.status()} ${settled.message} status=${settled.paymentStatus}`);
  }
  state.entities.payableSettlement = settled;
  state.mutations.push({ phase, action: 'settle-payable', entityId: settled.paymentTransactionId, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '03-finance-payable-settled.png'), fullPage: true });
}

async function salesCreateSubmit(page, state, report) {
  if (state.entities.salesOrder) throw new Error(`Sales order already tracked: ${state.entities.salesOrder.id}`);
  const productSearch = process.env.E2E_SALES_PRODUCT || '轻卤门腔（猪舌）120g';
  report.login = await loginRole(page, 'f006_sales_mgr', 'sales_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before sales mutation');
  report.intendedMutations.push({
    step: '销售创建订单并提交 OA 审批',
    create: { method: 'POST', endpoint: '/api/mobile/F006/sales/orders' },
    submit: {
      method: 'POST',
      endpoints: [
        '/api/mobile/F006/sales/orders/{id}/confirm',
        '/api/mobile/F006/sales/orders/{id}/submit-for-review',
      ],
    },
    target: { customer: '叮咚', product: productSearch },
    payloadBoundary: {
      quantity: 1,
      unitPrice: 10001,
      taxRate: 13,
      deliveryAddress: `${runKey} F006 测试收货地址`,
      remark: runKey,
    },
  });

  await page.goto(`${baseUrl}/sales/orders`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: /新建销售订单/ }).click();
  const dialog = page.locator('.el-dialog:visible').filter({ hasText: '新建销售订单' });
  await dialog.waitFor({ state: 'visible', timeout: 20_000 });
  await dialog.locator('.create-options-loading').waitFor({ state: 'hidden', timeout: 20_000 }).catch(() => {});

  const customerItem = dialog.locator('.el-form-item').filter({ hasText: '客户' }).first();
  await selectOption(page, customerItem.locator('.el-select').first(), '叮咚');
  const addressItem = dialog.locator('.el-form-item').filter({ hasText: '交货地址' }).first();
  await addressItem.locator('input').fill(`${runKey} F006 测试收货地址`);
  await dialog.locator('.el-form-item').filter({ hasText: '备注' }).first().locator('textarea').fill(runKey);

  const itemRow = dialog.locator('.item-row:not(.item-header)').first();
  await itemRow.waitFor({ state: 'visible', timeout: 20_000 });
  await selectOption(page, itemRow.locator('.el-select').first(), productSearch);
  await itemRow.locator('.el-input-number input').nth(0).fill('1');
  await itemRow.locator('.el-input-number input').nth(1).fill('10001');
  const packagingInput = itemRow.locator('input[placeholder="请选择箱规"], input[placeholder="默认箱规"]').first();
  if (await packagingInput.isVisible().catch(() => false)) {
    await packagingInput.click();
    const firstPackaging = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:not(.is-disabled)').first();
    await firstPackaging.waitFor({ state: 'visible', timeout: 15_000 });
    await firstPackaging.click();
  }

  const createResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/mobile/F006/sales/orders', { timeout: 25_000 });
  await dialog.getByRole('button', { name: '创建', exact: true }).click();
  const createResponse = await createResponsePromise;
  const created = compactBusinessData(await createResponse.json());
  report.actualMutations.push({ method: 'POST', url: createResponse.url(), statusCode: createResponse.status(), response: created });
  if (createResponse.status() >= 400 || !created.success || !created.id || !created.orderNumber) {
    throw new Error(`Sales create failed: ${createResponse.status()} ${created.message}`);
  }
  if (created.factoryId && created.factoryId !== 'F006') throw new Error(`Created SO tenant mismatch: ${created.factoryId}`);
  state.entities.salesOrder = created;
  state.mutations.push({ phase, action: 'create-sales-order', entityId: created.id, at: new Date().toISOString() });
  persistState(state);

  await dialog.waitFor({ state: 'hidden', timeout: 20_000 });
  const row = page.locator('.el-table__row:visible').filter({ hasText: created.orderNumber }).first();
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  const submitResponses = [];
  const responseListener = (response) => {
    const pathname = new URL(response.url()).pathname;
    if (response.request().method() === 'POST'
      && pathname.startsWith(`/api/mobile/F006/sales/orders/${created.id}/`)
      && (pathname.endsWith('/confirm') || pathname.endsWith('/submit-for-review'))) {
      submitResponses.push(response);
    }
  };
  page.on('response', responseListener);
  await row.locator('.business-action-row').getByRole('button').first().click();
  const confirmation = page.locator('.el-message-box:visible').filter({ hasText: created.orderNumber });
  await confirmation.getByRole('button', { name: '提交并判定', exact: true }).click();
  await page.waitForTimeout(1500);
  page.off('response', responseListener);
  for (const response of submitResponses) {
    const body = await response.json().catch(() => null);
    const compact = compactBusinessData(body);
    report.actualMutations.push({ method: 'POST', url: response.url(), statusCode: response.status(), response: compact });
    if (response.status() >= 400 || !compact.success) {
      throw new Error(`Sales submit failed: ${response.status()} ${compact.message}`);
    }
    state.entities.salesOrder = { ...state.entities.salesOrder, ...compact };
  }
  if (submitResponses.length === 0) throw new Error('Sales OA submit emitted no confirm/review mutation');
  state.mutations.push({ phase, action: 'submit-sales-order-oa', entityId: created.id, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '04-sales-order-submitted.png'), fullPage: true });
}

async function salesCancelUnproducible(page, state, report) {
  const salesOrder = state.entities.salesOrder;
  if (!salesOrder?.id || !salesOrder?.orderNumber) throw new Error('Tracked sales order is required');
  report.login = await loginRole(page, 'f006_sales_mgr', 'sales_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before sales cancellation mutation');
  report.intendedMutations.push({
    step: '取消因当前无已发布 Workflow 而无法排产的测试销售订单',
    method: 'POST',
    endpoint: '/api/mobile/F006/sales/orders/{id}/cancel',
    target: { salesOrderId: salesOrder.id, orderNumber: salesOrder.orderNumber },
    payloadBoundary: { reason: `其他: ${runKey} 当前无已发布 Workflow` },
  });

  await page.goto(`${baseUrl}/sales/orders`, { waitUntil: 'domcontentloaded' });
  const row = page.locator('.el-table__row:visible').filter({ hasText: salesOrder.orderNumber }).first();
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  if (salesOrder.status === 'FINANCE_APPROVED') {
    report.businessCancellationSkipped = {
      reason: '财务已批准订单不允许普通取消；页面无取消入口，后端要求驳回/退款流程',
      exactFinalCleanupRequired: true,
      downstreamProductionPlanCreated: false,
    };
    state.entities.supersededSalesOrders = [
      ...(state.entities.supersededSalesOrders || []),
      { ...salesOrder, cleanupRequired: true, reason: `${runKey} 当前无已发布 Workflow` },
    ];
    delete state.entities.salesOrder;
    delete state.entities.salesApproval;
    state.mutations.push({ phase, action: 'retire-unproducible-sales-order-for-exact-cleanup', entityId: salesOrder.id, at: new Date().toISOString() });
    persistState(state);
    await page.screenshot({ path: path.join(evidenceDir, '04a-unproducible-sales-order-retired.png'), fullPage: true });
    return;
  }
  await row.getByRole('button', { name: '更多', exact: true }).click();
  const cancelItem = page.locator('.el-popper:visible').getByText('取消', { exact: true }).first();
  await cancelItem.waitFor({ state: 'visible', timeout: 10_000 });
  await cancelItem.click();
  const dialog = page.locator('.el-dialog:visible').filter({ hasText: '取消销售订单' });
  await dialog.waitFor({ state: 'visible', timeout: 10_000 });
  await selectOption(page, dialog.locator('.el-select').first(), '其他');
  await dialog.locator('textarea').fill(`${runKey} 当前无已发布 Workflow`);
  const cancelResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/sales/orders/${salesOrder.id}/cancel`,
  { timeout: 25_000 });
  await dialog.getByRole('button', { name: '确认取消', exact: true }).click();
  const cancelResponse = await cancelResponsePromise;
  const cancelled = compactBusinessData(await cancelResponse.json());
  report.actualMutations.push({ method: 'POST', url: cancelResponse.url(), statusCode: cancelResponse.status(), response: cancelled });
  if (cancelResponse.status() >= 400 || !cancelled.success || cancelled.id !== salesOrder.id
      || cancelled.factoryId !== 'F006' || cancelled.status !== 'CANCELLED') {
    throw new Error(`Sales cancellation failed: ${cancelResponse.status()} ${cancelled.message} ${cancelled.status}`);
  }
  state.entities.supersededSalesOrders = [
    ...(state.entities.supersededSalesOrders || []),
    { ...salesOrder, ...cancelled, reason: `${runKey} 当前无已发布 Workflow` },
  ];
  delete state.entities.salesOrder;
  delete state.entities.salesApproval;
  state.mutations.push({ phase, action: 'cancel-unproducible-sales-order', entityId: salesOrder.id, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '04a-unproducible-sales-order-cancelled.png'), fullPage: true });
}

async function financeApproveSales(page, state, report) {
  const salesOrder = state.entities.salesOrder;
  if (!salesOrder?.id || !salesOrder?.orderNumber) throw new Error('Tracked sales order is required');
  if (salesOrder.status !== 'PENDING_FINANCE_REVIEW') {
    throw new Error(`Sales order is not pending finance review: ${salesOrder.status}`);
  }
  report.login = await loginRole(page, 'f006_finance_mgr', 'finance_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before sales approval mutation');
  report.intendedMutations.push({
    step: '财务主管在个人 OA 审批本轮销售订单',
    method: 'POST',
    endpoint: '/api/mobile/F006/workflow/instances/{instanceId}/actions',
    target: { salesOrderId: salesOrder.id, orderNumber: salesOrder.orderNumber },
    payloadBoundary: { action: 'APPROVE', expectedModule: 'SALES_ORDER' },
  });

  const initialReadback = await authenticatedGet(page, `/api/mobile/F006/sales/orders/${salesOrder.id}`);
  const initialOrder = compactBusinessData(initialReadback.body);
  if (initialReadback.status === 200 && initialOrder.success && initialOrder.id === salesOrder.id
      && initialOrder.factoryId === 'F006' && initialOrder.status === 'FINANCE_APPROVED') {
    report.reconciledPriorSuccessfulMutation = true;
    report.salesOrderReadback = initialOrder;
    state.entities.salesOrder = { ...salesOrder, ...initialOrder };
    state.mutations.push({ phase, action: 'reconcile-approved-sales-order-oa', entityId: salesOrder.id, at: new Date().toISOString() });
    persistState(state);
    await page.goto(`${baseUrl}/sales/orders`, { waitUntil: 'domcontentloaded' });
    await page.screenshot({ path: path.join(evidenceDir, '05-finance-sales-approved.png'), fullPage: true });
    return;
  }

  const pendingResponsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/mobile/F006/workflow/instances/pending');
  await page.goto(`${baseUrl}/workflow/pending?moduleCode=SALES_ORDER`, { waitUntil: 'domcontentloaded' });
  const pendingResponse = await pendingResponsePromise;
  const pendingBody = await pendingResponse.json();
  const pendingItems = pendingBody?.data?.items || pendingBody?.data?.content || pendingBody?.data?.records || [];
  const pending = pendingItems.find((item) => item.moduleCode === 'SALES_ORDER'
    && item.businessEntityId === salesOrder.id);
  report.pendingApproval = pending ? {
    instanceId: pending.instanceId,
    moduleCode: pending.moduleCode,
    businessEntityId: pending.businessEntityId,
    businessCode: pending.businessCode,
    businessAmount: pending.businessAmount,
    counterpartyName: pending.counterpartyName,
    currentNodeId: pending.currentNodeId,
    currentNodeLabel: pending.currentNodeLabel,
  } : null;
  if (!pending?.instanceId || pending.businessEntityId !== salesOrder.id) {
    throw new Error(`Exact OA todo not found for ${salesOrder.orderNumber}`);
  }

  const row = page.locator('.el-table__row:visible').filter({ hasText: salesOrder.orderNumber }).first();
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  const actionResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/workflow/instances/${pending.instanceId}/actions`,
  { timeout: 25_000 });
  await row.getByRole('button', { name: '通过', exact: true }).click();
  const confirmation = page.locator('.el-message-box:visible').filter({ hasText: '通过前请确认单据信息' });
  await confirmation.getByRole('button', { name: '审批通过', exact: true }).click();
  const actionResponse = await actionResponsePromise;
  const actionBody = await actionResponse.json();
  report.actualMutations.push({
    method: 'POST',
    url: actionResponse.url(),
    statusCode: actionResponse.status(),
    response: {
      success: actionBody?.success ?? null,
      message: actionBody?.message || null,
      instanceId: actionBody?.data?.instanceId || pending.instanceId,
      status: actionBody?.data?.status || null,
      currentNodeId: actionBody?.data?.currentNodeId || null,
    },
  });
  if (actionResponse.status() >= 400 || !actionBody?.success) {
    throw new Error(`Sales OA approval failed: ${actionResponse.status()} ${actionBody?.message}`);
  }

  const detailReadback = await authenticatedGet(page, `/api/mobile/F006/sales/orders/${salesOrder.id}`);
  const approved = compactBusinessData(detailReadback.body);
  report.salesOrderReadback = approved;
  if (detailReadback.status >= 400 || !approved.success || approved.id !== salesOrder.id
      || approved.factoryId !== 'F006' || approved.status !== 'FINANCE_APPROVED') {
    throw new Error(`Sales approval readback mismatch: ${detailReadback.status} ${approved.status}`);
  }
  state.entities.salesOrder = { ...salesOrder, ...approved };
  state.entities.salesApproval = {
    instanceId: pending.instanceId,
    businessEntityId: pending.businessEntityId,
    status: approved.status,
  };
  state.mutations.push({ phase, action: 'approve-sales-order-oa', entityId: pending.instanceId, at: new Date().toISOString() });
  persistState(state);
  await page.goto(`${baseUrl}/sales/orders`, { waitUntil: 'domcontentloaded' });
  await page.screenshot({ path: path.join(evidenceDir, '05-finance-sales-approved.png'), fullPage: true });
}

function compactProductionPlan(value) {
  return value ? {
    id: value.id || null,
    factoryId: value.factoryId || null,
    planNumber: value.planNumber || null,
    status: value.status || null,
    sourceType: value.sourceType || null,
    sourceOrderId: value.sourceOrderId || null,
    sourceOrderItemId: value.sourceOrderItemId || null,
    productTypeId: value.productTypeId || null,
    productName: value.productName || value.productTypeName || null,
    plannedQuantity: value.plannedQuantity ?? null,
    plannedUnit: value.plannedUnit || null,
    skipProcessReporting: value.skipProcessReporting ?? null,
  } : null;
}

async function productionCreatePlan(page, state, report) {
  const salesOrder = state.entities.salesOrder;
  if (!salesOrder?.id || salesOrder.status !== 'FINANCE_APPROVED') {
    throw new Error(`Finance-approved sales order is required: ${salesOrder?.status || 'missing'}`);
  }
  if (state.entities.productionPlan) throw new Error(`Production plan already tracked: ${state.entities.productionPlan.id}`);
  report.login = await loginRole(page, 'f006_production_mgr', 'production_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before production plan mutation');
  report.intendedMutations.push({
    step: '生产主管从已财审销售订单生成生产计划',
    method: 'POST',
    endpoint: '/api/mobile/F006/production-plans/batch-from-so',
    target: { salesOrderId: salesOrder.id, orderNumber: salesOrder.orderNumber },
    payloadBoundary: { notes: runKey, oneSelectedSalesOrderItem: true },
  });

  await page.goto(`${baseUrl}/production/plans?action=create&salesOrderId=${encodeURIComponent(salesOrder.id)}`, {
    waitUntil: 'domcontentloaded',
  });
  const dialog = page.locator('.el-dialog:visible').filter({ hasText: '新建生产计划' });
  await dialog.waitFor({ state: 'visible', timeout: 25_000 });
  await dialog.getByText(salesOrder.orderNumber, { exact: false }).waitFor({ state: 'visible', timeout: 25_000 });
  const selectedProductRows = dialog.locator('.sales-order-quantity-row');
  if (await selectedProductRows.count() !== 1) {
    throw new Error(`Expected exactly one selected sales product row, got ${await selectedProductRows.count()}`);
  }
  await dialog.locator('.el-form-item').filter({ hasText: '备注' }).first().locator('textarea').fill(runKey);
  const createResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/mobile/F006/production-plans/batch-from-so',
  { timeout: 30_000 });
  await dialog.getByRole('button', { name: '确定', exact: true }).click();
  const createResponse = await createResponsePromise;
  const createBody = await createResponse.json();
  const plans = Array.isArray(createBody?.data) ? createBody.data : [createBody?.data].filter(Boolean);
  const created = compactProductionPlan(plans[0]);
  report.actualMutations.push({
    method: 'POST',
    url: createResponse.url(),
    statusCode: createResponse.status(),
    response: { success: createBody?.success ?? null, message: createBody?.message || null, plans: plans.map(compactProductionPlan) },
  });
  if (createResponse.status() >= 400 || !createBody?.success || plans.length !== 1 || !created?.id
      || created.factoryId !== 'F006' || created.sourceOrderId !== salesOrder.id) {
    throw new Error(`Production plan create mismatch: ${createResponse.status()} ${createBody?.message} count=${plans.length}`);
  }
  state.entities.productionPlan = created;
  state.mutations.push({ phase, action: 'create-production-plan', entityId: created.id, at: new Date().toISOString() });
  persistState(state);
  await dialog.waitFor({ state: 'hidden', timeout: 20_000 });
  const row = page.locator('.el-table__row:visible').filter({ hasText: created.planNumber }).first();
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  await page.screenshot({ path: path.join(evidenceDir, '06-production-plan-created.png'), fullPage: true });
}

async function productionStartInspect(page, state, report) {
  const productionPlan = state.entities.productionPlan;
  if (!productionPlan?.id || productionPlan.factoryId !== 'F006') {
    throw new Error('Tracked F006 production plan is required');
  }
  if (!['PENDING', 'IN_PROGRESS'].includes(productionPlan.status)) {
    throw new Error(`Production plan cannot be prepared from status ${productionPlan.status}`);
  }
  report.login = await loginRole(page, 'f006_production_mgr', 'production_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before production batch mutation');
  report.intendedMutations.push({
    step: '生产主管从计划列表打开逐道录入并物化 Workflow 批次',
    method: 'POST',
    endpoint: `/api/mobile/F006/production-plans/${productionPlan.id}/create-batch`,
    target: { productionPlanId: productionPlan.id, planNumber: productionPlan.planNumber },
    payloadBoundary: { factoryId: 'F006', productTypeId: productionPlan.productTypeId },
  });

  const createBatchResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/production-plans/${productionPlan.id}/create-batch`,
  { timeout: 35_000 });
  await page.goto(`${baseUrl}/production/plans?openProcessEntryPlan=${encodeURIComponent(productionPlan.id)}`, {
    waitUntil: 'domcontentloaded',
  });
  const createBatchResponse = await createBatchResponsePromise;
  const createBatchBody = await createBatchResponse.json().catch(() => null);
  const batchData = createBatchBody?.data || {};
  report.actualMutations.push({
    method: 'POST',
    url: createBatchResponse.url(),
    statusCode: createBatchResponse.status(),
    response: {
      success: createBatchBody?.success ?? null,
      message: createBatchBody?.message || null,
      id: batchData.id || null,
      factoryId: batchData.factoryId || null,
      batchNumber: batchData.batchNumber || null,
      status: batchData.status || null,
      productionPlanId: batchData.productionPlanId || null,
    },
  });
  if (createBatchResponse.status() >= 400 || !createBatchBody?.success || !batchData.id
      || (batchData.factoryId && batchData.factoryId !== 'F006')) {
    throw new Error(`Production batch creation failed: ${createBatchResponse.status()} ${createBatchBody?.message}`);
  }

  const drawer = page.locator('.el-drawer:visible').filter({ hasText: '逐工序录入' });
  await drawer.waitFor({ state: 'visible', timeout: 35_000 });
  const addRowButton = drawer.getByRole('button', { name: '+ 新增行', exact: true }).first();
  await addRowButton.waitFor({ state: 'visible', timeout: 35_000 });
  await addRowButton.click();
  await drawer.getByRole('button', { name: '正式报工', exact: true }).first().waitFor({ state: 'visible', timeout: 35_000 });
  report.processSheetUi = await drawer.evaluate((root) => ({
    text: root.innerText,
    tabs: Array.from(root.querySelectorAll('[role="tab"]')).map((node) => ({
      text: node.textContent?.trim() || '',
      selected: node.getAttribute('aria-selected'),
    })),
    buttons: Array.from(root.querySelectorAll('button')).map((node) => ({
      text: node.textContent?.trim() || '',
      disabled: node.disabled,
      title: node.getAttribute('title'),
    })),
    inputs: Array.from(root.querySelectorAll('input')).map((node) => ({
      value: node.value,
      placeholder: node.getAttribute('placeholder'),
      disabled: node.disabled,
      role: node.getAttribute('role'),
    })),
  }));
  const planReadback = await authenticatedGet(page, `/api/mobile/F006/production-plans/${productionPlan.id}`);
  const compactPlan = compactProductionPlan(planReadback.body?.data || planReadback.body);
  report.productionPlanReadback = { statusCode: planReadback.status, data: compactPlan };
  if (planReadback.status >= 400 || !compactPlan?.id || compactPlan.factoryId !== 'F006'
      || compactPlan.status !== 'IN_PROGRESS') {
    throw new Error(`Production plan start readback mismatch: ${planReadback.status} ${compactPlan?.status}`);
  }
  state.entities.productionPlan = { ...productionPlan, ...compactPlan };
  state.entities.productionBatch = {
    id: batchData.id,
    factoryId: batchData.factoryId || 'F006',
    batchNumber: batchData.batchNumber,
    status: batchData.status,
    productionPlanId: batchData.productionPlanId || productionPlan.id,
  };
  state.mutations.push({ phase, action: 'create-production-batch-from-plan-ui', entityId: batchData.id, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '07-production-process-sheet-opened.png'), fullPage: true });
}

async function setVueInputValue(locator, value) {
  await locator.evaluate((input, nextValue) => {
    const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    descriptor?.set?.call(input, nextValue);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}

function compactProcessSheetResult(body) {
  const data = body?.data || {};
  return {
    success: body?.success ?? null,
    message: body?.message || null,
    clientRowId: data.clientRowId || null,
    processCode: data.processCode || null,
    batchNumber: data.batchNumber || null,
    rowStatus: data.rowStatus || null,
    submissionStatus: data.submissionStatus || null,
    materialized: data.materialized ?? null,
    outputs: Array.isArray(data.outputs) ? data.outputs.map((item) => ({
      workflowPortId: item.workflowPortId || null,
      materialTypeId: item.materialTypeId || null,
      materialName: item.materialName || null,
      quantity: item.quantity ?? null,
      unit: item.unit || null,
      batchNumber: item.batchNumber || null,
      finished: item.finished ?? null,
    })) : [],
    inputAllocations: Array.isArray(data.inputAllocations) ? data.inputAllocations.map((item) => ({
      materialBatchId: item.materialBatchId || null,
      batchNumber: item.batchNumber || null,
      materialTypeId: item.materialTypeId || null,
      materialName: item.materialName || null,
      quantity: item.quantity ?? null,
      unit: item.unit || null,
    })) : [],
  };
}

async function openTrackedProcessSheet(page, productionPlan) {
  await page.goto(`${baseUrl}/production/plans?openProcessEntryPlan=${encodeURIComponent(productionPlan.id)}`, {
    waitUntil: 'domcontentloaded',
  });
  const drawer = page.locator('.el-drawer:visible').filter({ hasText: '逐工序录入' });
  await drawer.waitFor({ state: 'visible', timeout: 35_000 });
  await drawer.getByRole('button', { name: '+ 新增行', exact: true }).first().waitFor({ state: 'visible', timeout: 35_000 });
  return drawer;
}

async function productionReportFirstProcess(page, state, report) {
  const mainQuantityRun = phase === 'production-report-first-main';
  const entityKey = mainQuantityRun ? 'firstMainProcessReport' : 'firstProcessReport';
  const materialQuantity = mainQuantityRun ? 0.2 : 0.01;
  const outputQuantity = mainQuantityRun ? 0.8 : 0.04;
  const productionPlan = state.entities.productionPlan;
  const productionBatch = state.entities.productionBatch;
  if (!productionPlan?.id || productionPlan.factoryId !== 'F006' || productionPlan.status !== 'IN_PROGRESS') {
    throw new Error('Tracked in-progress F006 production plan is required');
  }
  if (!productionBatch?.id || productionBatch.factoryId !== 'F006') {
    throw new Error('Tracked F006 production batch is required');
  }
  report.login = await loginRole(page, 'f006_production_mgr', 'production_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before first process reporting mutation');
  if (state.entities[entityKey]) throw new Error(`First process already reported: ${state.entities[entityKey].clientRowId}`);
  const priorReport = fs.existsSync(reportPath) ? JSON.parse(fs.readFileSync(reportPath, 'utf8')) : null;
  const reportResult = priorReport?.actualMutations?.find((item) => item?.method === 'POST'
    && item?.response?.success === true
    && item?.response?.submissionStatus === 'SUBMITTED')?.response;
  const priorResult = reportResult || (process.env.E2E_RECONCILE_FIRST_ROW_ID && process.env.E2E_RECONCILE_FIRST_BATCH
    ? {
        success: true,
        message: 'Reconciled from the captured successful HTTP 200 response',
        clientRowId: process.env.E2E_RECONCILE_FIRST_ROW_ID,
        processCode: null,
        batchNumber: process.env.E2E_RECONCILE_FIRST_BATCH,
        rowStatus: null,
        submissionStatus: 'SUBMITTED',
        materialized: true,
        outputs: [],
        inputAllocations: [],
      }
    : null);
  if (!mainQuantityRun && priorResult?.clientRowId && priorResult?.batchNumber) {
    const drawer = await openTrackedProcessSheet(page, productionPlan);
    await drawer.getByText(priorResult.batchNumber, { exact: true }).first().waitFor({ state: 'visible', timeout: 35_000 });
    report.reconciledPriorSuccessfulMutation = true;
    report.firstProcessUiReadback = {
      clientRowId: priorResult.clientRowId,
      batchNumber: priorResult.batchNumber,
      submissionStatus: 'SUBMITTED',
    };
    state.entities[entityKey] = priorResult;
    state.mutations.push({ phase, action: 'reconcile-first-process-report', entityId: priorResult.clientRowId, at: new Date().toISOString() });
    persistState(state);
    await page.screenshot({ path: path.join(evidenceDir, '08-production-first-process-reported.png'), fullPage: true });
    return;
  }
  report.intendedMutations.push({
    step: '生产主管逐道录入第一道原料处理工序',
    method: 'POST',
    endpoint: `/api/mobile/F006/production-plans/${productionPlan.id}/process-sheet/row/submit`,
    target: { productionPlanId: productionPlan.id, productionBatchId: productionBatch.id },
    payloadBoundary: { fourRawInputsKg: materialQuantity, outputKg: outputQuantity, workerCount: 1, time: '08:00-08:10' },
  });

  const drawer = await openTrackedProcessSheet(page, productionPlan);
  const firstTab = drawer.locator('[role="tab"]').first();
  if (await firstTab.getAttribute('aria-selected') !== 'true') await firstTab.click();
  await drawer.getByRole('button', { name: '+ 新增行', exact: true }).first().click();

  const materialRows = drawer.locator('[data-testid="material-input-total"]:visible');
  const materialCount = await materialRows.count();
  if (materialCount !== 4) throw new Error(`Expected four first-process raw material inputs, got ${materialCount}`);
  for (let index = 0; index < materialCount; index += 1) {
    const materialRow = materialRows.nth(index);
    const selector = materialRow.locator('[data-testid="port-selected"]');
    if (await selector.count()) await selector.click();
    const quantityInput = materialRow.locator('input[role="spinbutton"]');
    await quantityInput.fill(String(materialQuantity));
  }

  const outputLine = drawer.locator('[data-testid="workflow-output-line"]:visible').first();
  const outputSelector = outputLine.locator('[data-testid="port-selected"]');
  if (await outputSelector.count()) await outputSelector.click();
  await outputLine.locator('input[aria-label$="产出数量"]').fill(String(outputQuantity));
  await setVueInputValue(outputLine.locator('input[aria-label$="开始时间"]'), '08:00');
  await setVueInputValue(outputLine.locator('input[aria-label$="结束时间"]'), '08:10');
  const workerCount = outputLine.locator('input[aria-label$="人数"]');
  if ((await workerCount.inputValue()) !== '1') await workerCount.fill('1');

  const submit = drawer.getByRole('button', { name: '正式报工', exact: true }).first();
  report.beforeSubmit = {
    disabled: await submit.isDisabled(),
    title: await submit.getAttribute('title'),
    materialInputs: await materialRows.evaluateAll((rows) => rows.map((row) => row.textContent?.trim() || '')),
    output: await outputLine.innerText(),
  };
  if (report.beforeSubmit.disabled) throw new Error(`First process submit remained disabled: ${report.beforeSubmit.title}`);

  const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/production-plans/${productionPlan.id}/process-sheet/row/submit`,
  { timeout: 35_000 });
  await submit.click();
  const confirmation = page.locator('.el-message-box:visible').filter({ hasText: '确认正式报工' });
  await confirmation.getByRole('button', { name: '确认报工', exact: true }).click();
  const response = await responsePromise;
  const result = compactProcessSheetResult(await response.json().catch(() => null));
  report.actualMutations.push({ method: 'POST', url: response.url(), statusCode: response.status(), response: result });
  if (response.status() >= 400 || !result.success || result.submissionStatus !== 'SUBMITTED'
      || !result.clientRowId || !result.batchNumber) {
    throw new Error(`First process reporting failed: ${response.status()} ${result.message} ${result.submissionStatus}`);
  }
  state.entities[entityKey] = result;
  state.mutations.push({ phase, action: mainQuantityRun ? 'submit-first-process-main-report' : 'submit-first-process-report', entityId: result.clientRowId, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, mainQuantityRun
    ? '08b-production-first-process-main-reported.png'
    : '08-production-first-process-reported.png'), fullPage: true });
}

async function productionInspectSecondProcess(page, state, report) {
  const productionPlan = state.entities.productionPlan;
  if (!productionPlan?.id || productionPlan.factoryId !== 'F006' || !state.entities.firstProcessReport?.clientRowId) {
    throw new Error('Tracked first process report is required before inspecting the second process');
  }
  report.login = await loginRole(page, 'f006_production_mgr', 'production_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before second process inspection');
  const drawer = await openTrackedProcessSheet(page, productionPlan);
  const secondTab = drawer.locator('[role="tab"]').nth(1);
  await secondTab.click();
  const addRow = drawer.getByRole('button', { name: '+ 新增行', exact: true }).last();
  await addRow.waitFor({ state: 'visible', timeout: 20_000 });
  if (await addRow.isDisabled()) throw new Error(`Second process add row blocked: ${await addRow.getAttribute('title')}`);
  await addRow.click();
  await drawer.getByRole('button', { name: '正式报工', exact: true }).last().waitFor({ state: 'visible', timeout: 20_000 });
  report.processSheetUi = await drawer.evaluate((root) => ({
    text: root.innerText,
    tabs: Array.from(root.querySelectorAll('[role="tab"]')).map((node) => ({
      text: node.textContent?.trim() || '',
      selected: node.getAttribute('aria-selected'),
    })),
    buttons: Array.from(root.querySelectorAll('button')).filter((node) => node.offsetParent !== null).map((node) => ({
      text: node.textContent?.trim() || '',
      disabled: node.disabled,
      title: node.getAttribute('title'),
    })),
    inputs: Array.from(root.querySelectorAll('input')).filter((node) => node.offsetParent !== null).map((node) => ({
      value: node.value,
      placeholder: node.getAttribute('placeholder'),
      disabled: node.disabled,
      ariaLabel: node.getAttribute('aria-label'),
      role: node.getAttribute('role'),
    })),
  }));
  await page.screenshot({ path: path.join(evidenceDir, '08a-production-second-process-inspected.png'), fullPage: true });
}

async function productionReportSecondProcess(page, state, report) {
  const productionPlan = state.entities.productionPlan;
  const upstream = state.entities.firstMainProcessReport;
  if (!productionPlan?.id || productionPlan.factoryId !== 'F006' || productionPlan.status !== 'IN_PROGRESS') {
    throw new Error('Tracked in-progress F006 production plan is required');
  }
  if (!upstream?.clientRowId || !upstream.batchNumber) throw new Error('Tracked 0.8kg upstream report is required');
  if (state.entities.secondProcessReport) throw new Error(`Second process already reported: ${state.entities.secondProcessReport.clientRowId}`);
  report.login = await loginRole(page, 'f006_production_mgr', 'production_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before second process reporting mutation');
  report.intendedMutations.push({
    step: '生产主管逐道录入第二道定量包装工序',
    method: 'POST',
    endpoint: `/api/mobile/F006/production-plans/${productionPlan.id}/process-sheet/row/submit`,
    target: { productionPlanId: productionPlan.id, upstreamBatchNumber: upstream.batchNumber },
    payloadBoundary: { upstreamInputKg: 0.8, finishedOutputBoxes: 1, workerCount: 1, time: '08:20-08:30' },
  });

  const drawer = await openTrackedProcessSheet(page, productionPlan);
  await drawer.locator('[role="tab"]').nth(1).click();
  const addRow = drawer.getByRole('button', { name: '+ 新增行', exact: true }).last();
  await addRow.waitFor({ state: 'visible', timeout: 20_000 });
  if (await addRow.isDisabled()) throw new Error(`Second process add row blocked: ${await addRow.getAttribute('title')}`);
  await addRow.click();

  const sourceInput = drawer.locator('input[aria-label$="原料处理批次"]:visible');
  await sourceInput.waitFor({ state: 'visible', timeout: 20_000 });
  const sourceSelect = sourceInput.locator('xpath=ancestor::div[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]');
  await selectOption(page, sourceSelect, upstream.batchNumber);
  await drawer.locator('input[aria-label="本次投入量 (kg)"]:visible').fill('0.8');

  const outputLine = drawer.locator('[data-testid="workflow-output-line"]:visible').first();
  const outputSelector = outputLine.locator('[data-testid="port-selected"]');
  if (await outputSelector.count()) await outputSelector.click();
  await outputLine.locator('input[aria-label$="产出数量"]').fill('1');
  await setVueInputValue(outputLine.locator('input[aria-label$="开始时间"]'), '08:20');
  await setVueInputValue(outputLine.locator('input[aria-label$="结束时间"]'), '08:30');
  const workerCount = outputLine.locator('input[aria-label$="人数"]');
  if ((await workerCount.inputValue()) !== '1') await workerCount.fill('1');

  const submit = drawer.getByRole('button', { name: '正式报工', exact: true }).last();
  report.beforeSubmit = {
    disabled: await submit.isDisabled(),
    title: await submit.getAttribute('title'),
    selectedSource: await sourceInput.inputValue(),
    output: await outputLine.innerText(),
  };
  if (report.beforeSubmit.disabled) throw new Error(`Second process submit remained disabled: ${report.beforeSubmit.title}`);
  const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/production-plans/${productionPlan.id}/process-sheet/row/submit`,
  { timeout: 35_000 });
  await submit.click();
  const confirmation = page.locator('.el-message-box:visible').filter({ hasText: '确认正式报工' });
  await confirmation.getByRole('button', { name: '确认报工', exact: true }).click();
  const response = await responsePromise;
  const result = compactProcessSheetResult(await response.json().catch(() => null));
  report.actualMutations.push({ method: 'POST', url: response.url(), statusCode: response.status(), response: result });
  if (response.status() >= 400 || !result.success || result.submissionStatus !== 'SUBMITTED'
      || !result.clientRowId || !result.batchNumber) {
    throw new Error(`Second process reporting failed: ${response.status()} ${result.message} ${result.submissionStatus}`);
  }
  state.entities.secondProcessReport = result;
  state.mutations.push({ phase, action: 'submit-second-process-report', entityId: result.clientRowId, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '09-production-second-process-reported.png'), fullPage: true });
}

async function qualityInspectProductionBatch(page, state, report) {
  const productionBatch = state.entities.productionBatch;
  const secondProcessReport = state.entities.secondProcessReport;
  if (!productionBatch?.id || productionBatch.factoryId !== 'F006' || !secondProcessReport?.clientRowId) {
    throw new Error('Tracked F006 production batch with completed terminal process report is required');
  }
  if (state.entities.qualityInspection) throw new Error(`Quality inspection already tracked: ${state.entities.qualityInspection.id}`);
  report.login = await loginRole(page, 'f006_quality_insp', 'quality_inspector');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before quality inspection mutation');
  report.intendedMutations.push({
    step: '质检员对本轮生产批次创建合格检验记录',
    method: 'POST',
    endpoint: '/api/mobile/F006/processing/quality/inspections',
    target: { productionBatchId: String(productionBatch.id), batchNumber: productionBatch.batchNumber },
    payloadBoundary: { sampleSize: 1, passCount: 1, failCount: 0, result: 'PASS', notes: runKey },
  });

  await page.goto(`${baseUrl}/quality/inspections`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: '新建质检', exact: true }).click();
  const dialog = page.locator('.el-dialog:visible').filter({ hasText: '新建质检记录' });
  await dialog.waitFor({ state: 'visible', timeout: 20_000 });
  const batchItem = dialog.locator('.el-form-item').filter({ hasText: '生产批次' }).first();
  await selectOption(page, batchItem.locator('.el-select'), productionBatch.batchNumber);
  const numberInputs = dialog.locator('input[role="spinbutton"]');
  if (await numberInputs.count() !== 3) throw new Error(`Expected three quality count inputs, got ${await numberInputs.count()}`);
  await numberInputs.nth(0).fill('1');
  await numberInputs.nth(1).fill('1');
  await numberInputs.nth(2).fill('0');
  const resultItem = dialog.locator('.el-form-item').filter({ hasText: '检验结果' }).first();
  await selectOption(page, resultItem.locator('.el-select'), '合格');
  await dialog.locator('.el-form-item').filter({ hasText: '备注' }).locator('textarea').fill(runKey);

  const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/mobile/F006/processing/quality/inspections'
    && new URL(response.url()).searchParams.get('batchId') === String(productionBatch.id),
  { timeout: 30_000 });
  await dialog.getByRole('button', { name: '确定', exact: true }).click();
  const response = await responsePromise;
  const body = await response.json().catch(() => null);
  const data = body?.data || {};
  const inspection = {
    success: body?.success ?? null,
    message: body?.message || null,
    id: data.id || null,
    factoryId: data.factoryId || 'F006',
    productionBatchId: String(data.batchId || data.productionBatchId || productionBatch.id),
    result: data.result || null,
    sampleSize: data.sampleSize ?? null,
    passCount: data.passCount ?? null,
    failCount: data.failCount ?? null,
    inspectorId: data.inspectorId ?? null,
    notes: data.notes || null,
  };
  report.actualMutations.push({ method: 'POST', url: response.url(), statusCode: response.status(), response: inspection });
  if (response.status() >= 400 || !inspection.success || !inspection.id || inspection.factoryId !== 'F006'
      || inspection.productionBatchId !== String(productionBatch.id) || !['PASS', 'PASSED'].includes(inspection.result)) {
    throw new Error(`Quality inspection failed: ${response.status()} ${inspection.message} ${inspection.result}`);
  }
  state.entities.qualityInspection = inspection;
  state.mutations.push({ phase, action: 'create-passing-quality-inspection', entityId: inspection.id, at: new Date().toISOString() });
  persistState(state);
  await dialog.waitFor({ state: 'hidden', timeout: 20_000 });
  const search = page.locator('input[placeholder="搜索质检编号/批次号"]');
  await search.fill(runKey);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.locator('.el-table__row:visible').filter({ hasText: runKey }).first().waitFor({ state: 'visible', timeout: 20_000 });
  await page.screenshot({ path: path.join(evidenceDir, '10-quality-inspection-passed.png'), fullPage: true });
}

async function productionSettlePlan(page, state, report) {
  const productionPlan = state.entities.productionPlan;
  if (!productionPlan?.id || productionPlan.factoryId !== 'F006' || productionPlan.status !== 'IN_PROGRESS'
      || !state.entities.secondProcessReport?.clientRowId) {
    throw new Error('Tracked in-progress F006 plan with terminal report is required');
  }
  if (state.entities.productionSettlement) throw new Error(`Production settlement already tracked: ${state.entities.productionSettlement.settlementId}`);
  report.login = await loginRole(page, 'f006_production_mgr', 'production_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before production settlement mutation');
  report.intendedMutations.push({
    step: '生产主管核对逐道报工并结单',
    method: 'POST',
    endpoint: `/api/mobile/F006/production-plans/${productionPlan.id}/settle`,
    target: { productionPlanId: productionPlan.id, planNumber: productionPlan.planNumber },
    payloadBoundary: { idempotencyKey: `${runKey}-settle`, confirm: true, expectedFinishedQuantity: 1, unit: '盒' },
  });

  await page.goto(`${baseUrl}/production/plans`, { waitUntil: 'domcontentloaded' });
  const search = page.locator('input[placeholder="搜索计划编号/产品名称"]');
  await search.fill(productionPlan.planNumber);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  const row = page.locator('.el-table__row:visible').filter({ hasText: productionPlan.planNumber }).first();
  await row.waitFor({ state: 'visible', timeout: 25_000 });
  await row.getByRole('button', { name: '核对结单', exact: true }).click();
  const dialog = page.locator('.el-dialog:visible').filter({ hasText: '核对结单' });
  await dialog.waitFor({ state: 'visible', timeout: 25_000 });
  const confirmButton = dialog.getByRole('button', { name: '确认结单并结束计划', exact: true });
  await confirmButton.waitFor({ state: 'visible', timeout: 35_000 });
  await dialog.getByText('正在按逐道报工自动带入结单数据…', { exact: true })
    .waitFor({ state: 'hidden', timeout: 35_000 });
  report.prefillUi = {
    text: await dialog.innerText(),
    disabled: await confirmButton.isDisabled(),
  };
  if (report.prefillUi.disabled) throw new Error(`Production settlement blocked by UI prefill: ${report.prefillUi.text}`);

  const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/production-plans/${productionPlan.id}/settle`,
  { timeout: 40_000 });
  await confirmButton.click();
  const response = await responsePromise;
  const body = await response.json().catch(() => null);
  const data = body?.data || {};
  const settlement = {
    success: body?.success ?? null,
    message: body?.message || null,
    settlementId: data.settlementId || data.id || null,
    factoryId: data.factoryId || 'F006',
    productionPlanId: data.productionPlanId || productionPlan.id,
    status: data.status || null,
    postingStatus: data.postingStatus || null,
    finishedQuantity: data.finishedQuantity ?? data.actualFinishedQuantity ?? null,
    quantityUnit: data.quantityUnit || data.unit || null,
  };
  report.actualMutations.push({ method: 'POST', url: response.url(), statusCode: response.status(), response: settlement });
  if (response.status() >= 400 || !settlement.success || settlement.factoryId !== 'F006'
      || settlement.productionPlanId !== productionPlan.id) {
    throw new Error(`Production settlement failed: ${response.status()} ${settlement.message}`);
  }
  const planReadback = await authenticatedGet(page, `/api/mobile/F006/production-plans/${productionPlan.id}`);
  const settledPlan = compactProductionPlan(planReadback.body?.data || planReadback.body);
  const settlementReadback = await authenticatedGet(page, `/api/mobile/F006/production-plans/${productionPlan.id}/settlement`);
  report.planReadback = { statusCode: planReadback.status, data: settledPlan };
  report.settlementReadback = { statusCode: settlementReadback.status, body: settlementReadback.body };
  if (planReadback.status >= 400 || !settledPlan?.id || settledPlan.factoryId !== 'F006' || settledPlan.status !== 'COMPLETED') {
    throw new Error(`Production plan settlement readback mismatch: ${planReadback.status} ${settledPlan?.status}`);
  }
  state.entities.productionPlan = { ...productionPlan, ...settledPlan };
  state.entities.productionSettlement = settlement;
  state.mutations.push({ phase, action: 'settle-production-plan', entityId: settlement.settlementId || productionPlan.id, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '10-production-plan-settled.png'), fullPage: true });
}

async function warehouseConfirmProductionReceipt(page, state, report) {
  const productionPlan = state.entities.productionPlan;
  const productionBatch = state.entities.productionBatch;
  const productionSettlement = state.entities.productionSettlement;
  if (!productionPlan?.id || productionPlan.factoryId !== 'F006' || productionPlan.status !== 'COMPLETED'
      || !productionBatch?.id || productionBatch.factoryId !== 'F006'
      || productionSettlement?.postingStatus !== 'PENDING_WAREHOUSE_RECEIPT') {
    throw new Error('Tracked completed F006 production plan pending warehouse receipt is required');
  }
  if (state.entities.productionWarehouseReceipt) {
    throw new Error(`Production warehouse receipt already tracked: ${state.entities.productionWarehouseReceipt.settlementId}`);
  }
  report.login = await loginRole(page, 'f006_warehouse_mgr', 'warehouse_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before production warehouse receipt mutation');
  report.intendedMutations.push({
    step: '仓库经理核对生产报产并确认成品入库',
    method: 'POST',
    endpoint: `/api/mobile/F006/production-plans/${productionPlan.id}/warehouse-receipt`,
    target: {
      productionPlanId: productionPlan.id,
      planNumber: productionPlan.planNumber,
      productionBatchId: productionBatch.id,
      batchNumber: productionBatch.batchNumber,
    },
    payloadBoundary: { exactFullReceipt: true, expectedQuantity: 1, expectedUnit: '盒', note: runKey },
  });

  await page.goto(`${baseUrl}/production/plans`, { waitUntil: 'domcontentloaded' });
  const search = page.locator('input[placeholder="搜索计划编号/产品名称"]');
  await search.waitFor({ state: 'visible', timeout: 25_000 });
  await search.fill(productionPlan.planNumber);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  const row = page.locator('.el-table__row:visible').filter({ hasText: productionPlan.planNumber }).first();
  await row.waitFor({ state: 'visible', timeout: 25_000 });
  const receiptEntry = row.getByRole('button', { name: '仓库确认入库', exact: true });
  await receiptEntry.waitFor({ state: 'visible', timeout: 35_000 });
  await receiptEntry.click();

  const dialog = page.locator('.el-dialog:visible').filter({ hasText: '仓库确认入库' });
  await dialog.waitFor({ state: 'visible', timeout: 25_000 });
  await dialog.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 35_000 }).catch(() => {});
  const confirmButton = dialog.getByRole('button', { name: '确认入库', exact: true });
  await confirmButton.waitFor({ state: 'visible', timeout: 35_000 });
  await dialog.locator('textarea[placeholder="可记录称重单号、交接人、现场说明"]').fill(runKey);
  report.prefillUi = {
    text: await dialog.innerText(),
    disabled: await confirmButton.isDisabled(),
  };
  if (report.prefillUi.disabled) throw new Error(`Production warehouse receipt blocked by UI prefill: ${report.prefillUi.text}`);

  const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/production-plans/${productionPlan.id}/warehouse-receipt`,
  { timeout: 40_000 });
  await confirmButton.click();
  const response = await responsePromise;
  const body = await response.json().catch(() => null);
  const data = body?.data || {};
  const receipt = {
    success: body?.success ?? null,
    message: data.message || body?.message || null,
    settlementId: data.settlementId || productionSettlement.settlementId || null,
    productionPlanId: data.productionPlanId || productionPlan.id,
    planNumber: data.planNumber || productionPlan.planNumber,
    productionReportedQuantity: data.productionReportedQuantity ?? null,
    warehouseReceivedQuantity: data.warehouseReceivedQuantity ?? null,
    quantityUnit: data.quantityUnit || null,
    postingStatus: data.postingStatus || null,
    finishedGoodsBatchId: data.finishedGoodsBatchId || null,
    outputLines: Array.isArray(data.outputLines) ? data.outputLines : [],
  };
  report.actualMutations.push({ method: 'POST', url: response.url(), statusCode: response.status(), response: receipt });
  if (response.status() >= 400 || !receipt.success || receipt.productionPlanId !== productionPlan.id
      || !['POSTED', 'POSTED_WITH_TOLERANCE'].includes(receipt.postingStatus)) {
    throw new Error(`Production warehouse receipt failed: ${response.status()} ${receipt.message} ${receipt.postingStatus}`);
  }

  const settlementReadback = await authenticatedGet(page, `/api/mobile/F006/production-plans/${productionPlan.id}/settlement`);
  const readbackSettlement = settlementReadback.body?.data || {};
  const batchReadback = await authenticatedGet(page, `/api/mobile/F006/processing/batches/${productionBatch.id}`);
  const readbackBatch = batchReadback.body?.data || batchReadback.body || {};
  report.settlementReadback = { statusCode: settlementReadback.status, body: settlementReadback.body };
  report.productionBatchReadback = { statusCode: batchReadback.status, body: batchReadback.body };
  if (settlementReadback.status >= 400 || !['POSTED', 'POSTED_WITH_TOLERANCE'].includes(readbackSettlement.postingStatus)) {
    throw new Error(`Warehouse receipt settlement readback mismatch: ${settlementReadback.status} ${readbackSettlement.postingStatus}`);
  }
  if (batchReadback.status >= 400 || String(readbackBatch.factoryId || 'F006') !== 'F006'
      || String(readbackBatch.id) !== String(productionBatch.id) || readbackBatch.status !== 'COMPLETED') {
    throw new Error(`Warehouse receipt batch readback mismatch: ${batchReadback.status} ${readbackBatch.id} ${readbackBatch.status}`);
  }
  state.entities.productionSettlement = { ...productionSettlement, postingStatus: readbackSettlement.postingStatus };
  state.entities.productionWarehouseReceipt = receipt;
  state.entities.productionBatch = {
    ...productionBatch,
    status: readbackBatch.status,
    batchNumber: readbackBatch.batchNumber || productionBatch.batchNumber,
  };
  state.mutations.push({ phase, action: 'confirm-production-warehouse-receipt', entityId: receipt.finishedGoodsBatchId || receipt.settlementId, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '11-warehouse-production-receipt.png'), fullPage: true });
}

async function salesCreateDeliveryAllocate(page, state, report) {
  const salesOrder = state.entities.salesOrder;
  const finishedGoodsBatchId = state.entities.productionWarehouseReceipt?.finishedGoodsBatchId;
  const productionBatch = state.entities.productionBatch;
  const qualityInspection = state.entities.qualityInspection;
  if (!salesOrder?.id || salesOrder.factoryId !== 'F006' || !finishedGoodsBatchId
      || productionBatch?.status !== 'COMPLETED' || !qualityInspection?.id
      || !['PASS', 'PASSED'].includes(qualityInspection.result)) {
    throw new Error('Tracked F006 sales order with received and quality-passed finished goods is required');
  }
  if (state.entities.salesDeliveryAllocation) {
    throw new Error(`Sales delivery allocation already tracked: ${state.entities.salesDelivery.id}`);
  }
  report.login = await loginRole(page, 'f006_sales_mgr', 'sales_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before sales delivery mutation');
  report.intendedMutations.push({
    step: '销售主管创建母发货单、子发运单，并分配刚入库的精确成品批次',
    methods: [
      { method: 'POST', endpoint: '/api/mobile/F006/sales/deliveries' },
      { method: 'POST', endpoint: '/api/mobile/F006/sales/deliveries/{masterId}/shipments' },
      { method: 'POST', endpoint: '/api/mobile/F006/sales-deliveries/items/{itemId}/batch-allocations' },
    ],
    target: {
      salesOrderId: salesOrder.id,
      orderNumber: salesOrder.orderNumber,
      newlyReceivedFinishedGoodsBatchId: finishedGoodsBatchId,
      newlyReceivedBatchNumber: productionBatch.batchNumber,
      allocationPolicy: 'UI FIFO recommendation; sales order is not batch-locked',
    },
    payloadBoundary: { deliveryQuantity: 1, unit: '盒', address: runKey, logisticsCompany: 'E2E冷链' },
  });

  await page.goto(`${baseUrl}/sales/orders/${salesOrder.id}`, { waitUntil: 'domcontentloaded' });
  await page.getByText(salesOrder.orderNumber, { exact: true }).first().waitFor({ state: 'visible', timeout: 30_000 });
  let delivery = state.entities.salesDelivery;
  if (!delivery) {
    const priorReport = fs.existsSync(reportPath) ? JSON.parse(fs.readFileSync(reportPath, 'utf8')) : null;
    const capturedDelivery = priorReport?.actualMutations?.find((item) => item?.method === 'POST'
      && new URL(item.url).pathname === '/api/mobile/F006/sales/deliveries'
      && item?.response?.success === true)?.response;
    const priorDelivery = capturedDelivery || (process.env.E2E_RECONCILE_DELIVERY_ID && process.env.E2E_RECONCILE_DELIVERY_NUMBER
      ? {
          success: true,
          message: 'Reconciled from the captured successful HTTP 200 response',
          id: process.env.E2E_RECONCILE_DELIVERY_ID,
          factoryId: 'F006',
          deliveryNumber: process.env.E2E_RECONCILE_DELIVERY_NUMBER,
          salesOrderId: salesOrder.id,
          status: 'PENDING_SPLIT',
          items: [],
        }
      : null);
    if (priorDelivery?.id && priorDelivery?.deliveryNumber && priorDelivery?.salesOrderId === salesOrder.id) {
      delivery = priorDelivery;
      state.entities.salesDelivery = delivery;
      state.mutations.push({ phase, action: 'reconcile-created-sales-delivery', entityId: delivery.id, at: new Date().toISOString() });
      persistState(state);
      report.reconciledPriorSuccessfulMutation = true;
    }
  }
  if (!delivery) {
    const createEntry = page.getByRole('button').filter({ hasText: /发货单/ }).first();
    await createEntry.waitFor({ state: 'visible', timeout: 35_000 });
    if (await createEntry.isDisabled()) throw new Error(`Sales delivery action disabled: ${await page.locator('body').innerText()}`);
    await createEntry.click();
    const dialog = page.locator('.el-dialog:visible').filter({ hasText: '创建发货单' });
    await dialog.waitFor({ state: 'visible', timeout: 20_000 });
    const addressInput = dialog.locator('input[placeholder="优先使用订单交货地址；订单为空时回退客户主档收货地址"]');
    if (!(await addressInput.inputValue()).trim()) await addressInput.fill(`F006 ${runKey} 测试收货地址`);
    await dialog.locator('input[placeholder="如：顺丰冷链"]').fill('E2E冷链');
    report.deliveryDialog = await dialog.innerText();
    if (!report.deliveryDialog.includes('剩余可安排 1盒')) {
      throw new Error(`Sales delivery prefill does not show exact remaining 1 box: ${report.deliveryDialog}`);
    }
    const createResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/mobile/F006/sales/deliveries', { timeout: 35_000 });
    await dialog.getByRole('button', { name: '创建发货单', exact: true }).click();
    const createResponse = await createResponsePromise;
    const createBody = await createResponse.json().catch(() => null);
    const data = createBody?.data || {};
    delivery = {
      success: createBody?.success ?? null,
      message: createBody?.message || null,
      id: data.id || null,
      factoryId: data.factoryId || 'F006',
      deliveryNumber: data.deliveryNumber || null,
      salesOrderId: data.salesOrderId || salesOrder.id,
      customerId: data.customerId || null,
      status: data.status || null,
      totalAmount: data.totalAmount ?? null,
      items: Array.isArray(data.items) ? data.items : [],
    };
    report.actualMutations.push({ method: 'POST', url: createResponse.url(), statusCode: createResponse.status(), response: delivery });
    if (createResponse.status() >= 400 || !delivery.success || !delivery.id || delivery.factoryId !== 'F006'
        || delivery.salesOrderId !== salesOrder.id || !delivery.deliveryNumber) {
      throw new Error(`Sales delivery creation failed: ${createResponse.status()} ${delivery.message}`);
    }
    state.entities.salesDelivery = delivery;
    state.mutations.push({ phase, action: 'create-sales-delivery', entityId: delivery.id, at: new Date().toISOString() });
    persistState(state);
  }

  const deliveryTab = page.getByRole('tab').filter({ hasText: '发货记录' });
  if (await deliveryTab.getAttribute('aria-selected') !== 'true') await deliveryTab.click();
  const masterRow = page.locator('.el-table__row:visible').filter({ hasText: delivery.deliveryNumber }).first();
  await masterRow.waitFor({ state: 'visible', timeout: 30_000 });
  await masterRow.evaluate((row) => {
    const wrap = row.closest('.el-table')?.querySelector('.el-table__body-wrapper .el-scrollbar__wrap');
    if (wrap) {
      wrap.scrollLeft = wrap.scrollWidth;
      wrap.dispatchEvent(new Event('scroll'));
    }
  });

  let shipment = state.entities.salesShipment;
  if (!shipment) {
    const createShipmentEntry = page.locator('button:visible').filter({ hasText: '新建分批发运' }).first();
    await createShipmentEntry.waitFor({ state: 'visible', timeout: 30_000 });
    await createShipmentEntry.click();
    const shipmentDialog = page.locator('.el-dialog:visible').filter({ hasText: '新建分批发运' });
    await shipmentDialog.waitFor({ state: 'visible', timeout: 20_000 });
    await shipmentDialog.locator('.el-form-item').filter({ hasText: '物流公司' }).locator('input').fill('E2E冷链');
    await shipmentDialog.locator('.el-form-item').filter({ hasText: '物流/运单号' }).locator('input').fill(runKey);
    const shipmentDialogText = await shipmentDialog.innerText();
    report.shipmentDialog = shipmentDialogText;
    if (!shipmentDialogText.includes('母单剩余') || !shipmentDialogText.includes('1盒')) {
      throw new Error(`Child shipment did not inherit the exact remaining 1 box: ${shipmentDialogText}`);
    }
    const shipmentResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/mobile/F006/sales/deliveries/${delivery.id}/shipments`, { timeout: 35_000 });
    await shipmentDialog.getByRole('button', { name: '创建子发运单', exact: true }).click();
    const shipmentResponse = await shipmentResponsePromise;
    const shipmentBody = await shipmentResponse.json().catch(() => null);
    const shipmentData = shipmentBody?.data || {};
    shipment = {
      success: shipmentBody?.success ?? null,
      message: shipmentBody?.message || null,
      id: shipmentData.id || null,
      factoryId: shipmentData.factoryId || 'F006',
      deliveryNumber: shipmentData.deliveryNumber || null,
      parentDeliveryId: shipmentData.parentDeliveryId || delivery.id,
      salesOrderId: shipmentData.salesOrderId || salesOrder.id,
      status: shipmentData.status || null,
      items: Array.isArray(shipmentData.items) ? shipmentData.items : [],
    };
    report.actualMutations.push({ method: 'POST', url: shipmentResponse.url(), statusCode: shipmentResponse.status(), response: shipment });
    if (shipmentResponse.status() >= 400 || !shipment.success || !shipment.id || shipment.factoryId !== 'F006'
        || shipment.parentDeliveryId !== delivery.id || !shipment.deliveryNumber) {
      throw new Error(`Child shipment creation failed: ${shipmentResponse.status()} ${shipment.message}`);
    }
    state.entities.salesShipment = shipment;
    state.mutations.push({ phase, action: 'create-sales-shipment', entityId: shipment.id, at: new Date().toISOString() });
    persistState(state);
  }

  const deliveryRow = page.locator('.el-table__row:visible').filter({ hasText: shipment.deliveryNumber }).first();
  await deliveryRow.waitFor({ state: 'visible', timeout: 30_000 });
  await deliveryRow.evaluate((row) => {
    const wrap = row.closest('.el-table')?.querySelector('.el-table__body-wrapper .el-scrollbar__wrap');
    if (wrap) {
      wrap.scrollLeft = wrap.scrollWidth;
      wrap.dispatchEvent(new Event('scroll'));
    }
  });
  const allocateEntry = page.locator('button:visible').filter({ hasText: /分配批次|修改批次/ }).first();
  await allocateEntry.waitFor({ state: 'visible', timeout: 30_000 });
  await allocateEntry.click();
  const allocationDialog = page.locator('.el-dialog:visible').filter({ hasText: '成品批次分配' });
  await allocationDialog.waitFor({ state: 'visible', timeout: 25_000 });
  report.allocationDialog = await allocationDialog.innerText();
  if (!report.allocationDialog.includes('分配合计: 1盒')) {
    throw new Error(`FIFO did not prefill the exact 1-box batch allocation: ${report.allocationDialog}`);
  }
  const allocatedBatchNumber = (await allocationDialog.locator('.el-table__row:visible').first().locator('td').first().innerText()).trim();
  if (!allocatedBatchNumber) throw new Error(`FIFO allocation exposed no traceable batch number: ${report.allocationDialog}`);
  report.fifoAllocation = {
    allocatedBatchNumber,
    newlyReceivedBatchNumber: productionBatch.batchNumber,
    usedNewlyReceivedBatch: allocatedBatchNumber === productionBatch.batchNumber,
    quantity: 1,
    unit: '盒',
  };
  const allocationResponses = [];
  const onAllocationResponse = async (response) => {
    if (response.request().method() === 'POST'
        && /\/api\/mobile\/F006\/sales-deliveries\/items\/[^/]+\/batch-allocations$/.test(new URL(response.url()).pathname)) {
      allocationResponses.push({
        url: response.url(),
        statusCode: response.status(),
        body: await response.json().catch(() => null),
      });
    }
  };
  page.on('response', onAllocationResponse);
  await allocationDialog.getByRole('button', { name: '确认分配', exact: true }).click();
  await allocationDialog.waitFor({ state: 'hidden', timeout: 35_000 });
  page.off('response', onAllocationResponse);
  report.allocationResponses = allocationResponses;
  if (allocationResponses.length !== 1 || allocationResponses[0].statusCode >= 400
      || allocationResponses[0].body?.success !== true) {
    throw new Error(`Sales delivery batch allocation mismatch: ${JSON.stringify(allocationResponses)}`);
  }
  state.entities.salesDeliveryAllocation = {
    finishedGoodsBatchId: allocationResponses[0].body?.data?.[0]?.finishedGoodsBatchId || null,
    batchNumber: allocatedBatchNumber,
    newlyReceivedFinishedGoodsBatchId: finishedGoodsBatchId,
    newlyReceivedBatchNumber: productionBatch.batchNumber,
    quantity: 1,
    response: allocationResponses[0].body?.data || null,
  };
  state.mutations.push({ phase, action: 'allocate-finished-goods-batch', entityId: allocatedBatchNumber, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '13-sales-delivery-allocated.png'), fullPage: true });
}

async function financeInvoiceAndCollect(page, state, report) {
  const salesOrder = state.entities.salesOrder;
  const shipment = state.entities.warehouseShipmentConfirmation;
  if (!salesOrder?.id || salesOrder.factoryId !== 'F006' || !shipment?.id
      || shipment.factoryId !== 'F006' || shipment.status !== 'SHIPPED') {
    throw new Error('Tracked shipped F006 sales order is required');
  }
  if (state.entities.invoice || state.entities.customerPayment) {
    throw new Error('Invoice/payment already tracked; refusing duplicate finance mutations');
  }
  report.login = await loginRole(page, 'f006_finance_mgr', 'finance_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before invoice/payment mutations');
  report.intendedMutations.push({
    step: '财务主管核对系统自动开票待办，完成审核、PDF 开具、全额收款登记与确认',
    methods: [
      { method: 'GET', endpoint: '/api/mobile/F006/finance/invoices/by-sales-order/{salesOrderId}' },
      { method: 'POST', endpoint: '/api/mobile/F006/finance/invoices/{invoiceId}/approve' },
      { method: 'POST', endpoint: '/api/mobile/F006/finance/invoices/{invoiceId}/issue', multipart: true },
      { method: 'POST', endpoint: '/api/mobile/F006/finance/payments/record' },
      { method: 'POST', endpoint: '/api/mobile/F006/finance/payments/{paymentId}/verify' },
    ],
    target: { salesOrderId: salesOrder.id, orderNumber: salesOrder.orderNumber, shippedDeliveryId: shipment.id },
    payloadBoundary: { fullOrderAmount: salesOrder.totalAmount, reference: runKey, invoicePdf: 'L4-CF-2-factory_admin1-direct.pdf' },
  });

  await page.goto(`${baseUrl}/finance/invoices`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('main').getByText('开票管理', { exact: true }).waitFor({ state: 'visible', timeout: 35_000 });
  const manualEntry = page.getByRole('button', { name: /申请开票|手工开票/, exact: true });
  if (await manualEntry.count() !== 0) throw new Error('Manual invoice entry must not be exposed');
  const automatic = await waitForAutomaticInvoice(page, salesOrder.id);
  const requestedInvoice = automatic.invoice;
  report.automaticInvoiceObservation = {
    statusCode: automatic.response.status,
    salesOrderId: salesOrder.id,
    recordCount: automatic.all.length,
    requestedInvoice,
  };
  if (!requestedInvoice.id || requestedInvoice.factoryId !== 'F006'
      || requestedInvoice.salesOrderId !== salesOrder.id || requestedInvoice.status !== 'REQUESTED'
      || Number(requestedInvoice.amount) !== Number(salesOrder.totalAmount)) {
    throw new Error(`Automatic invoice mismatch: ${JSON.stringify(requestedInvoice)}`);
  }
  state.entities.invoice = requestedInvoice;
  const automaticInvoiceLedger = {
    operation: 'AUTO_CREATE_INVOICE_RECORD',
    recordId: requestedInvoice.id,
    factoryId: 'F006',
    salesOrderId: salesOrder.id,
    orderNumber: salesOrder.orderNumber,
    expectedMutation: {
      method: 'SYSTEM_EVENT_AFTER_DELIVERY',
      status: 'REQUESTED',
      amount: salesOrder.totalAmount,
      exactlyOneRecord: true,
    },
    actualMutation: {
      method: 'GET_READBACK',
      statusCode: automatic.response.status,
      status: requestedInvoice.status,
      amount: requestedInvoice.amount,
      retained: true,
    },
    cleanupOutcome: 'RETAINED_FOR_FINANCE_AUDIT; no production cleanup attempted',
  };
  report.automaticInvoiceMutationLedger = automaticInvoiceLedger;
  state.observations = [
    ...(state.observations || []),
    { phase, action: 'observe-automatic-invoice', ...automaticInvoiceLedger, at: new Date().toISOString() },
  ];
  persistState(state);

  const invoiceRow = page.locator('.el-table__row:visible').filter({ hasText: requestedInvoice.invoiceNumber }).first();
  await invoiceRow.waitFor({ state: 'visible', timeout: 30_000 });
  const approveResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/finance/invoices/${requestedInvoice.id}/approve`, { timeout: 35_000 });
  await invoiceRow.getByRole('button', { name: '审核', exact: true }).click();
  const approvePrompt = page.locator('.el-message-box:visible').filter({ hasText: '确认审核通过' });
  await approvePrompt.getByRole('button', { name: '确定', exact: true }).click();
  const approveResponse = await approveResponsePromise;
  const approveBody = await approveResponse.json().catch(() => null);
  const approvedInvoice = approveBody?.data || {};
  report.actualMutations.push({ method: 'POST', url: approveResponse.url(), statusCode: approveResponse.status(), response: approvedInvoice });
  if (approveResponse.status() >= 400 || !approveBody?.success || approvedInvoice.id !== requestedInvoice.id
      || approvedInvoice.factoryId !== 'F006' || approvedInvoice.status !== 'APPROVED') {
    throw new Error(`Invoice approval mismatch: ${approveResponse.status()} ${approveBody?.message} ${approvedInvoice.status}`);
  }
  state.entities.invoice = approvedInvoice;
  state.mutations.push({ phase, action: 'approve-invoice', entityId: approvedInvoice.id, at: new Date().toISOString() });
  persistState(state);

  await invoiceRow.getByRole('button', { name: '开具', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
  await invoiceRow.getByRole('button', { name: '开具', exact: true }).click();
  const issueDialog = page.locator('.el-dialog:visible').filter({ hasText: '开具发票' });
  await issueDialog.waitFor({ state: 'visible', timeout: 20_000 });
  const invoicePdf = path.resolve('docs/qa-audits/2026-05-12-r1c-evidence/L4-CF-2-factory_admin1-direct.pdf');
  await issueDialog.locator('input[type="file"]').setInputFiles(invoicePdf);
  const issueResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/finance/invoices/${requestedInvoice.id}/issue`, { timeout: 45_000 });
  await issueDialog.getByRole('button', { name: '确认开具', exact: true }).click();
  const issueResponse = await issueResponsePromise;
  const issueBody = await issueResponse.json().catch(() => null);
  const issuedInvoice = issueBody?.data || {};
  report.actualMutations.push({ method: 'POST', url: issueResponse.url(), statusCode: issueResponse.status(), response: issuedInvoice });
  if (issueResponse.status() >= 400 || !issueBody?.success || issuedInvoice.id !== requestedInvoice.id
      || issuedInvoice.factoryId !== 'F006' || issuedInvoice.status !== 'ISSUED' || !issuedInvoice.invoicePdfUrl) {
    throw new Error(`Invoice issue mismatch: ${issueResponse.status()} ${issueBody?.message} ${issuedInvoice.status}`);
  }
  state.entities.invoice = issuedInvoice;
  state.mutations.push({ phase, action: 'issue-invoice-pdf', entityId: issuedInvoice.id, at: new Date().toISOString() });
  persistState(state);

  await page.goto(`${baseUrl}/finance/payments`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('main').getByText('收款管理', { exact: true }).waitFor({ state: 'visible', timeout: 35_000 });
  const paymentEntry = page.getByRole('button', { name: '录入收款', exact: true });
  await paymentEntry.waitFor({ state: 'visible', timeout: 30_000 });
  await paymentEntry.click();
  const paymentDialog = page.locator('.el-dialog:visible').filter({ hasText: '录入收款' });
  await paymentDialog.waitFor({ state: 'visible', timeout: 20_000 });
  const paymentOrderSelect = paymentDialog.getByRole('combobox').first();
  await paymentOrderSelect.click();
  await paymentOrderSelect.fill(salesOrder.orderNumber);
  const paymentOrderOption = page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: salesOrder.orderNumber }).first();
  await paymentOrderOption.waitFor({ state: 'visible', timeout: 20_000 });
  await paymentOrderOption.click();
  report.paymentDialog = await paymentDialog.innerText();
  if (!report.paymentDialog.includes('可收') || !report.paymentDialog.includes('¥10001.00')) {
    throw new Error(`Payment dialog does not expose exact outstanding amount: ${report.paymentDialog}`);
  }
  await paymentDialog.locator('input[placeholder="银行流水号/支付凭证号"]').fill(runKey);
  await paymentDialog.locator('.el-form-item').filter({ hasText: '备注' }).locator('textarea').fill(runKey);
  const paymentResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/mobile/F006/finance/payments/record', { timeout: 35_000 });
  await paymentDialog.getByRole('button', { name: '提交', exact: true }).click();
  const paymentResponse = await paymentResponsePromise;
  const paymentBody = await paymentResponse.json().catch(() => null);
  const payment = paymentBody?.data || {};
  report.actualMutations.push({ method: 'POST', url: paymentResponse.url(), statusCode: paymentResponse.status(), response: payment });
  if (paymentResponse.status() >= 400 || !paymentBody?.success || !payment.id || payment.factoryId !== 'F006'
      || payment.salesOrderId !== salesOrder.id || payment.status !== 'PENDING'
      || Number(payment.amount) !== Number(salesOrder.totalAmount)) {
    throw new Error(`Payment creation mismatch: ${paymentResponse.status()} ${paymentBody?.message} ${payment.status}`);
  }
  state.entities.customerPayment = payment;
  state.mutations.push({ phase, action: 'record-customer-payment', entityId: payment.id, at: new Date().toISOString() });
  persistState(state);
  const keyword = page.locator('input[placeholder="搜索 客户/收款单号/发票号"]');
  await keyword.waitFor({ state: 'visible', timeout: 30_000 });
  await keyword.fill(payment.paymentNumber);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  const paymentRow = page.locator('.el-table__row:visible').filter({ hasText: payment.paymentNumber }).first();
  await paymentRow.waitFor({ state: 'visible', timeout: 30_000 });
  const verifyResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/finance/payments/${payment.id}/verify`, { timeout: 35_000 });
  await paymentRow.getByRole('button', { name: '确认', exact: true }).click();
  const verifyPrompt = page.locator('.el-message-box:visible').filter({ hasText: '确认此笔收款' });
  await verifyPrompt.getByRole('button', { name: '确定', exact: true }).click();
  const verifyResponse = await verifyResponsePromise;
  const verifyBody = await verifyResponse.json().catch(() => null);
  const verifiedPayment = verifyBody?.data || {};
  report.actualMutations.push({ method: 'POST', url: verifyResponse.url(), statusCode: verifyResponse.status(), response: verifiedPayment });
  if (verifyResponse.status() >= 400 || !verifyBody?.success || verifiedPayment.id !== payment.id
      || verifiedPayment.factoryId !== 'F006' || verifiedPayment.status !== 'VERIFIED') {
    throw new Error(`Payment verification mismatch: ${verifyResponse.status()} ${verifyBody?.message} ${verifiedPayment.status}`);
  }
  state.entities.customerPayment = verifiedPayment;
  state.mutations.push({ phase, action: 'verify-customer-payment', entityId: payment.id, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '16-finance-invoice-payment-complete.png'), fullPage: true });
}

async function financeVerifyTrackedPayment(page, state, report) {
  const invoice = state.entities.invoice;
  const payment = state.entities.customerPayment;
  const salesOrder = state.entities.salesOrder;
  if (!invoice?.id || invoice.factoryId !== 'F006' || invoice.status !== 'ISSUED'
      || !payment?.id || payment.factoryId !== 'F006' || payment.status !== 'PENDING'
      || payment.salesOrderId !== salesOrder?.id) {
    throw new Error('Tracked issued invoice and pending F006 customer payment are required');
  }
  report.login = await loginRole(page, 'f006_finance_mgr', 'finance_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before payment verification');
  report.intendedMutations.push({
    step: '财务主管确认本次待核收款',
    method: 'POST',
    endpoint: `/api/mobile/F006/finance/payments/${payment.id}/verify`,
    target: { paymentId: payment.id, paymentNumber: payment.paymentNumber, salesOrderId: salesOrder.id },
  });
  await page.goto(`${baseUrl}/finance/payments`, { waitUntil: 'domcontentloaded' });
  const keyword = page.locator('input[placeholder="搜索 客户/收款单号/发票号"]');
  await keyword.waitFor({ state: 'visible', timeout: 30_000 });
  await keyword.fill(payment.paymentNumber);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  const paymentRow = page.locator('.el-table__row:visible').filter({ hasText: payment.paymentNumber }).first();
  await paymentRow.waitFor({ state: 'visible', timeout: 30_000 });
  await paymentRow.getByRole('button', { name: '确认', exact: true }).click();
  const verifyPrompt = page.locator('.el-message-box:visible').filter({ hasText: '确认此笔收款' });
  const verifyResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/finance/payments/${payment.id}/verify`, { timeout: 35_000 });
  await verifyPrompt.getByRole('button', { name: '确定', exact: true }).click();
  const verifyResponse = await verifyResponsePromise;
  const verifyBody = await verifyResponse.json().catch(() => null);
  const verifiedPayment = verifyBody?.data || {};
  report.actualMutations.push({ method: 'POST', url: verifyResponse.url(), statusCode: verifyResponse.status(), response: verifiedPayment });
  if (verifyResponse.status() >= 400 || !verifyBody?.success || verifiedPayment.id !== payment.id
      || verifiedPayment.factoryId !== 'F006' || verifiedPayment.status !== 'VERIFIED') {
    throw new Error(`Payment verification mismatch: ${verifyResponse.status()} ${verifyBody?.message} ${verifiedPayment.status}`);
  }
  state.entities.customerPayment = verifiedPayment;
  state.mutations.push({ phase, action: 'verify-customer-payment', entityId: payment.id, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '16-finance-invoice-payment-complete.png'), fullPage: true });
}

async function financeReplaceConflictingPayment(page, state, report) {
  const payment = state.entities.customerPayment;
  const salesOrder = state.entities.salesOrder;
  if (!payment?.id || payment.factoryId !== 'F006' || payment.status !== 'PENDING'
      || payment.salesOrderId !== salesOrder?.id || payment.paymentReference !== runKey) {
    throw new Error('Tracked conflicting pending F006 customer payment is required');
  }
  report.login = await loginRole(page, 'f006_finance_mgr', 'finance_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before replacing pending payment');
  report.intendedMutations.push({
    step: '财务主管驳回测试误用采购流水号的待核收款，并以独立 AR 流水号重新登记',
    methods: [
      { method: 'POST', endpoint: `/api/mobile/F006/finance/payments/${payment.id}/reject` },
      { method: 'POST', endpoint: '/api/mobile/F006/finance/payments/record' },
    ],
    target: { oldPaymentId: payment.id, oldPaymentNumber: payment.paymentNumber, salesOrderId: salesOrder.id },
    payloadBoundary: { amount: salesOrder.totalAmount, paymentReference: `${runKey}-AR` },
  });
  await page.goto(`${baseUrl}/finance/payments`, { waitUntil: 'domcontentloaded' });
  const keyword = page.locator('input[placeholder="搜索 客户/收款单号/发票号"]');
  await keyword.waitFor({ state: 'visible', timeout: 30_000 });
  await keyword.fill(payment.paymentNumber);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  const paymentRow = page.locator('.el-table__row:visible').filter({ hasText: payment.paymentNumber }).first();
  await paymentRow.waitFor({ state: 'visible', timeout: 30_000 });
  await paymentRow.getByRole('button', { name: '驳回', exact: true }).click();
  const rejectPrompt = page.locator('.el-message-box:visible').filter({ hasText: '请输入驳回原因' });
  await rejectPrompt.locator('input').fill('测试误用了采购付款流水号，改用独立 AR 流水号');
  const rejectResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/finance/payments/${payment.id}/reject`, { timeout: 35_000 });
  await rejectPrompt.getByRole('button', { name: '确定', exact: true }).click();
  const rejectResponse = await rejectResponsePromise;
  const rejectBody = await rejectResponse.json().catch(() => null);
  const rejected = rejectBody?.data || {};
  report.actualMutations.push({ method: 'POST', url: rejectResponse.url(), statusCode: rejectResponse.status(), response: rejected });
  if (rejectResponse.status() >= 400 || !rejectBody?.success || rejected.id !== payment.id
      || rejected.factoryId !== 'F006' || rejected.status !== 'REJECTED') {
    throw new Error(`Payment rejection mismatch: ${rejectResponse.status()} ${rejectBody?.message} ${rejected.status}`);
  }
  state.entities.supersededCustomerPayments = [...(state.entities.supersededCustomerPayments || []), rejected];
  delete state.entities.customerPayment;
  state.mutations.push({ phase, action: 'reject-conflicting-customer-payment', entityId: payment.id, at: new Date().toISOString() });
  persistState(state);

  await keyword.fill('');
  await page.getByRole('button', { name: '重置', exact: true }).click();
  await page.getByRole('button', { name: '录入收款', exact: true }).click();
  const paymentDialog = page.locator('.el-dialog:visible').filter({ hasText: '录入收款' });
  await paymentDialog.waitFor({ state: 'visible', timeout: 20_000 });
  const paymentOrderSelect = paymentDialog.getByRole('combobox').first();
  await paymentOrderSelect.click();
  await paymentOrderSelect.fill(salesOrder.orderNumber);
  const paymentOrderOption = page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: salesOrder.orderNumber }).first();
  await paymentOrderOption.waitFor({ state: 'visible', timeout: 20_000 });
  await paymentOrderOption.click();
  await paymentDialog.locator('input[placeholder="银行流水号/支付凭证号"]').fill(`${runKey}-AR`);
  await paymentDialog.locator('.el-form-item').filter({ hasText: '备注' }).locator('textarea').fill(runKey);
  const createResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/mobile/F006/finance/payments/record', { timeout: 35_000 });
  await paymentDialog.getByRole('button', { name: '提交', exact: true }).click();
  const createResponse = await createResponsePromise;
  const createBody = await createResponse.json().catch(() => null);
  const replacement = createBody?.data || {};
  report.actualMutations.push({ method: 'POST', url: createResponse.url(), statusCode: createResponse.status(), response: replacement });
  if (createResponse.status() >= 400 || !createBody?.success || !replacement.id || replacement.factoryId !== 'F006'
      || replacement.salesOrderId !== salesOrder.id || replacement.status !== 'PENDING'
      || replacement.paymentReference !== `${runKey}-AR` || Number(replacement.amount) !== Number(salesOrder.totalAmount)) {
    throw new Error(`Replacement payment mismatch: ${createResponse.status()} ${createBody?.message} ${replacement.status}`);
  }
  state.entities.customerPayment = replacement;
  state.mutations.push({ phase, action: 'record-customer-payment-with-ar-reference', entityId: replacement.id, at: new Date().toISOString() });
  persistState(state);
  await page.screenshot({ path: path.join(evidenceDir, '16a-finance-payment-reference-corrected.png'), fullPage: true });
}

async function productionDiscoverWorkflows(page, report) {
  report.login = await loginRole(page, 'f006_production_mgr', 'production_manager');
  if (report.login.factoryId !== 'F006') throw new Error('F006 proof failed before workflow discovery');
  const discovery = await page.evaluate(async ({ origin }) => {
    const token = localStorage.getItem('cretas_access_token');
    if (!token) throw new Error('Authenticated token missing from browser storage');
    const headers = { Authorization: `Bearer ${token}` };
    const productsResponse = await fetch(`${origin}/api/mobile/F006/product-types/active`, { headers });
    const productsBody = await productsResponse.json();
    const products = Array.isArray(productsBody?.data)
      ? productsBody.data
      : Array.isArray(productsBody?.data?.content) ? productsBody.data.content : [];
    const finishedGoods = products.filter((item) => item.productCategory === 'FINISHED_PRODUCT');
    const results = await Promise.all(finishedGoods.map(async (item) => {
      const url = `${origin}/api/mobile/F006/product-process-workflows/producing?finishedGoodProductTypeId=${encodeURIComponent(item.id)}`;
      const response = await fetch(url, { headers });
      const body = await response.json().catch(() => null);
      const workflows = Array.isArray(body?.data?.workflows) ? body.data.workflows : [];
      const detailedWorkflows = await Promise.all(workflows.map(async (workflow) => {
        const definitionUrl = `${origin}/api/mobile/F006/product-process-workflows/${encodeURIComponent(workflow.ownerProductTypeId)}/versions/${workflow.definitionVersion}`;
        const definitionResponse = await fetch(definitionUrl, { headers });
        const definitionBody = await definitionResponse.json().catch(() => null);
        const definition = definitionBody?.data || {};
        return {
          workflowId: workflow.workflowId,
          definitionVersion: workflow.definitionVersion,
          ownerProductTypeId: workflow.ownerProductTypeId,
          ownerProductName: workflow.ownerProductName,
          workflowType: workflow.workflowType,
          terminalOutputs: workflow.terminalOutputs,
          definitionStatus: definitionResponse.status,
          nodes: Array.isArray(definition.nodes) ? definition.nodes.map((node) => ({
            id: node.id,
            type: node.type,
            label: node.label || node.data?.label || node.data?.name || null,
            productTypeId: node.productTypeId || node.data?.productTypeId || node.data?.materialTypeId || null,
            unit: node.unit || node.data?.unit || null,
            quantity: node.quantity ?? node.data?.quantity ?? node.data?.standardQuantity ?? null,
          })) : [],
          edgeCount: Array.isArray(definition.edges) ? definition.edges.length : 0,
        };
      }));
      return {
        productTypeId: item.id,
        code: item.code,
        name: item.name,
        unit: item.unit,
        status: response.status,
        workflows: detailedWorkflows,
      };
    }));
    const inventoryChecks = await Promise.all(['SOP-20260817-01', '冻猪蹄'].map(async (keyword) => {
      const url = `${origin}/api/mobile/F006/material-batches?page=1&size=200&keyword=${encodeURIComponent(keyword)}`;
      const response = await fetch(url, { headers });
      const body = await response.json().catch(() => null);
      const batches = Array.isArray(body?.data?.content) ? body.data.content : [];
      return {
        keyword,
        status: response.status,
        batches: batches.map((batch) => ({
          id: batch.id,
          batchNumber: batch.batchNumber,
          materialTypeId: batch.materialTypeId,
          materialTypeName: batch.materialTypeName,
          quantity: batch.quantity,
          usedQuantity: batch.usedQuantity,
          reservedQuantity: batch.reservedQuantity,
          availableQuantity: batch.availableQuantity,
          unit: batch.unit,
          status: batch.status,
          warehouseCode: batch.warehouseCode,
        })),
      };
    }));
    return {
      productStatus: productsResponse.status,
      finishedGoodCount: finishedGoods.length,
      producible: results.filter((item) => item.workflows.length > 0),
      inventoryChecks,
    };
  }, { origin: baseUrl });
  report.workflowDiscovery = discovery;
  if (discovery.productStatus !== 200 || discovery.producible.length === 0) {
    throw new Error(`No producible F006 finished good discovered; productStatus=${discovery.productStatus}`);
  }
}

async function main() {
  const state = loadState();
  const report = {
    runKey,
    phase,
    factoryId: 'F006',
    startedAt: new Date().toISOString(),
    intendedMutations: [],
    actualMutations: [],
    consoleErrors: [],
    httpErrors: [],
    observedDataResponses: [],
    result: 'RUNNING',
  };
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  page.on('console', (message) => {
    if (message.type() === 'error') report.consoleErrors.push({ text: message.text(), url: message.location().url || null });
  });
  page.on('response', (response) => {
    if (/\/api\/mobile\/F006\/(suppliers|raw-material-types\/active|sales\/orders)/.test(response.url())) {
      report.observedDataResponses.push({ method: response.request().method(), url: response.url(), status: response.status() });
    }
    if (response.status() >= 400) report.httpErrors.push({ method: response.request().method(), url: response.url(), status: response.status() });
  });
  try {
    if (phase === 'procurement-create-submit') await procurementCreateSubmit(page, state, report);
    else if (phase === 'warehouse-receive') await warehouseReceive(page, state, report);
    else if (phase === 'finance-settle-payable') await financeSettlePayable(page, state, report);
    else if (phase === 'sales-create-submit') await salesCreateSubmit(page, state, report);
    else if (phase === 'sales-cancel-unproducible') await salesCancelUnproducible(page, state, report);
    else if (phase === 'finance-approve-sales') await financeApproveSales(page, state, report);
    else if (phase === 'production-create-plan') await productionCreatePlan(page, state, report);
    else if (phase === 'production-start-inspect') await productionStartInspect(page, state, report);
    else if (phase === 'production-report-first') await productionReportFirstProcess(page, state, report);
    else if (phase === 'production-report-first-main') await productionReportFirstProcess(page, state, report);
    else if (phase === 'production-inspect-second') await productionInspectSecondProcess(page, state, report);
    else if (phase === 'production-report-second') await productionReportSecondProcess(page, state, report);
    else if (phase === 'quality-inspect-batch') await qualityInspectProductionBatch(page, state, report);
    else if (phase === 'production-settle') await productionSettlePlan(page, state, report);
    else if (phase === 'warehouse-confirm-production-receipt') await warehouseConfirmProductionReceipt(page, state, report);
    else if (phase === 'sales-create-delivery-allocate') await salesCreateDeliveryAllocate(page, state, report);
    else if (phase === 'finance-invoice-and-collect') await financeInvoiceAndCollect(page, state, report);
    else if (phase === 'finance-verify-payment') await financeVerifyTrackedPayment(page, state, report);
    else if (phase === 'finance-replace-conflicting-payment') await financeReplaceConflictingPayment(page, state, report);
    else if (phase === 'production-discover-workflows') await productionDiscoverWorkflows(page, report);
    else throw new Error(`Unknown phase: ${phase}`);
    report.result = 'PASS';
  } catch (error) {
    report.result = 'FAIL';
    report.error = error instanceof Error ? error.message : String(error);
    await page.screenshot({ path: path.join(evidenceDir, `${phase}-failure.png`), fullPage: true }).catch(() => {});
    process.exitCode = 1;
  } finally {
    report.finishedAt = new Date().toISOString();
    fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
    await context.close();
    await browser.close();
  }
  console.log(JSON.stringify({ result: report.result, reportPath, statePath, entities: state.entities }, null, 2));
}

await main();
