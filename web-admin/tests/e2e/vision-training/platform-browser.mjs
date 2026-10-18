import { chromium, expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const base = process.env.VISION_ENTRY_BASE || 'http://127.0.0.1:31761';
const username = process.env.VISION_ENTRY_USERNAME?.trim();
const password = process.env.VISION_ENTRY_PASSWORD;
const evidenceDir = process.env.EVIDENCE_DIR;
const factoryA = 'VISION-ENTRY-A';
const factoryB = 'VISION-ENTRY-B';
const factoryAName = process.env.VISION_ENTRY_FACTORY_A_NAME || 'Vision Entry A';
const factoryBName = process.env.VISION_ENTRY_FACTORY_B_NAME || 'Vision Entry B';
const runId = process.env.VISION_ENTRY_RUN_ID || `browser-${Date.now()}`;
const specializedRecipeId = process.env.VISION_ENTRY_SPECIALIZED_RECIPE_ID?.trim() || '';
const specializedRegistry = Boolean(specializedRecipeId);

if (!username || !password || !evidenceDir) {
  throw new Error('VISION_ENTRY_BASE, VISION_ENTRY_USERNAME, VISION_ENTRY_PASSWORD, and EVIDENCE_DIR are required');
}

const baseUrl = new URL(base);
if (!['127.0.0.1', 'localhost', '::1'].includes(baseUrl.hostname)) {
  throw new Error(`Refusing non-loopback browser target: ${baseUrl.origin}`);
}
if (baseUrl.port !== '31761') {
  throw new Error(`Expected Vite port 31761, got ${baseUrl.port || '(default)'}`);
}

const out = resolve(evidenceDir);
await mkdir(out, { recursive: true });
const visionApiPrefix = '/api/platform/factory-assembly/factories/';

const apiReceipts = [];
const consoleErrors = [];
const loginReceipts = [];
const savedReceipts = [];
const previewReceipts = [];

function apiPath(response) {
  try {
    return new URL(response.url()).pathname;
  } catch {
    return response.url();
  }
}

function isApiResponse(response) {
  return apiPath(response).startsWith('/api/');
}

function responseSummary(response) {
  return {
    method: response.request().method(),
    path: apiPath(response),
    status: response.status(),
    ok: response.ok(),
  };
}

function isApiRequest(request) {
  try {
    return new URL(request.url()).pathname.startsWith('/api/');
  } catch {
    return false;
  }
}

function isVisionPath(path) {
  return path.startsWith(visionApiPrefix) && path.includes('/vision-training/');
}

async function fillInput(testId, value) {
  const field = page.getByTestId(testId);
  // Element Plus forwards input attributes to the native input, while select
  // attributes remain on a wrapper. Locate the actual rendered control.
  const input = await field.evaluate(element => element.matches('input, textarea'))
    ? field : field.locator('input').last();
  await input.fill(value);
}

async function addCreatedOption(testId, value) {
  const field = page.getByTestId(testId);
  await field.click();
  const input = field.locator('input').last();
  await input.fill(value);
  await input.press('Enter');
}

async function waitForVisionSetup(factoryId) {
  const setup = page.getByTestId('vision-training-setup');
  await expect(setup).toBeVisible({ timeout: 45_000 });
  await expect(setup).toContainText(factoryId, { timeout: 45_000 });
  await expect(page.getByTestId('vision-template')).toBeVisible({ timeout: 45_000 });
  await expect(setup.locator('.el-skeleton')).toHaveCount(0, { timeout: 45_000 });
  await expect(setup.getByText('重新读取', { exact: true })).toHaveCount(0);
  return setup;
}

async function chooseGenericTemplate() {
  const field = page.getByTestId('vision-template');
  await field.click();
  const dropdown = page.locator('.el-select-dropdown:visible').last();
  await expect(dropdown).toBeVisible();
  const options = (await dropdown.locator('.el-select-dropdown__item').allTextContents())
    .map((value) => value.trim()).filter(Boolean);
  const genericLabel = '包装有无与缺件检测';
  if (!options.includes(genericLabel)) {
    throw new Error(`Generic vision template is unavailable; options=${JSON.stringify(options)}`);
  }
  const specializedLabel = '六膳门托盘标签（本厂专用）';
  await dropdown.getByText(genericLabel, { exact: true }).click();
  return { options, genericLabel, specializedLabel };
}

async function chooseSpecializedTemplate(label) {
  const field = page.getByTestId('vision-template');
  await field.click();
  const dropdown = page.locator('.el-select-dropdown:visible').last();
  await expect(dropdown).toBeVisible();
  const options = (await dropdown.locator('.el-select-dropdown__item').allTextContents())
    .map((value) => value.trim()).filter(Boolean);
  if (!options.includes(label)) {
    throw new Error(`Specialized vision template is unavailable; options=${JSON.stringify(options)}`);
  }
  await dropdown.getByText(label, { exact: true }).click();
  return options;
}

async function chooseRecipe(recipeId) {
  await page.getByTestId('vision-recipe').click();
  const dropdown = page.locator('.el-select-dropdown:visible').last();
  await expect(dropdown).toBeVisible();
  const option = dropdown.locator('.el-select-dropdown__item').filter({ hasText: recipeId });
  await expect(option).toHaveCount(1);
  const classes = await option.getAttribute('class');
  if (classes?.includes('is-disabled')) throw new Error(`Specialized recipe ${recipeId} is disabled`);
  await option.click();
}

async function routeToFactory(factoryId, factoryName) {
  const query = `factoryId=${encodeURIComponent(factoryId)}&factoryName=${encodeURIComponent(factoryName)}`;
  await page.goto(`${base}/factory-onboarding?${query}`, { waitUntil: 'domcontentloaded' });
  return waitForVisionSetup(factoryId);
}

async function waitForReceipt(predicate, description) {
  await expect.poll(() => apiReceipts.some(predicate), { timeout: 45_000, message: description }).toBe(true);
}

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
const page = await context.newPage();

page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(`[console] ${message.text()}`);
});
page.on('pageerror', (error) => consoleErrors.push(`[pageerror] ${error.message}`));
page.on('request', (request) => {
  if (!isApiRequest(request)) return;
  const path = new URL(request.url()).pathname;
  apiReceipts.push({ method: request.method(), path, status: 'pending' });
});
page.on('response', (response) => {
  if (!isApiResponse(response)) return;
  const receipt = responseSummary(response);
  const pending = apiReceipts.find((item) => item.path === receipt.path
    && item.method === receipt.method && item.status === 'pending');
  if (pending) Object.assign(pending, receipt);
  else apiReceipts.push(receipt);
});

let result;
try {
  await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('input[placeholder="请输入手机号或用户名"]')).toBeVisible();
  await page.locator('input[placeholder="请输入手机号或用户名"]').fill(username);
  await page.locator('input[placeholder="请输入密码"]').fill(password);
  const loginResponsePromise = page.waitForResponse((response) => {
    const path = apiPath(response);
    return response.request().method() === 'POST' && path.endsWith('/auth/unified-login');
  });
  await page.getByRole('button', { name: /登\s*录/ }).click();
  const loginResponse = await loginResponsePromise;
  const loginBody = await loginResponse.json();
  const loginData = loginBody?.data;
  if (!loginResponse.ok() || loginBody?.success !== true || loginData?.actorType !== 'PLATFORM_ADMIN') {
    throw new Error(`Platform login namespace assertion failed (HTTP ${loginResponse.status()})`);
  }
  loginReceipts.push({
    method: 'POST', path: apiPath(loginResponse), status: loginResponse.status(),
    success: loginBody.success === true, actorType: loginData.actorType,
    userIdPresent: loginData.userId != null,
  });
  await page.screenshot({ path: resolve(out, '00-platform-login.png'), fullPage: true });

  const setupA = await routeToFactory(factoryA, factoryAName);
  const templateA = await chooseGenericTemplate();
  const product = `VISION-ENTRY-PRODUCT-${runId}`;
  const workstation = `VISION-ENTRY-WORKSTATION-${runId}`;
  const packaging = `VISION-ENTRY-PACKAGING-${runId}`;
  const dataVersion = `VISION-ENTRY-DATA-${runId}`;
  await fillInput('vision-workstation', workstation);
  await addCreatedOption('vision-products', product);
  await addCreatedOption('vision-packaging', packaging);
  await fillInput('vision-data_version', dataVersion);
  await fillInput('vision-recipe_version', `VISION-ENTRY-RECIPE-${runId}`);
  await fillInput('vision-human_standard_version', `VISION-ENTRY-HUMAN-${runId}`);
  await fillInput('vision-independent_acceptance_version', `VISION-ENTRY-ACCEPT-${runId}`);
  await page.screenshot({ path: resolve(out, '01-factory-a-form.png'), fullPage: true });

  const previewResponsePromise = page.waitForResponse((response) => {
    return response.request().method() === 'POST' && isVisionPath(apiPath(response))
      && apiPath(response).endsWith('/vision-training/preview');
  });
  await page.getByRole('button', { name: '预览训练流程', exact: true }).click();
  const previewResponse = await previewResponsePromise;
  if (!previewResponse.ok()) throw new Error(`Vision preview failed with HTTP ${previewResponse.status()}`);
  const previewBody = await previewResponse.json();
  previewReceipts.push({
    method: 'POST', path: apiPath(previewResponse), status: previewResponse.status(),
    success: previewBody?.success === true, previewIdPresent: previewBody?.data?.preview_id != null,
    factoryId: previewBody?.data?.spec?.factory_id,
  });
  await expect(page.getByTestId('vision-preview')).toBeVisible();
  await page.screenshot({ path: resolve(out, '02-factory-a-preview.png'), fullPage: true });

  const saveResponsePromise = page.waitForResponse((response) => {
    return response.request().method() === 'POST' && isVisionPath(apiPath(response))
      && apiPath(response).endsWith('/vision-training/drafts');
  });
  await page.getByRole('button', { name: '保存训练项目', exact: true }).click();
  const saveResponse = await saveResponsePromise;
  if (!saveResponse.ok()) throw new Error(`Vision draft save failed with HTTP ${saveResponse.status()}`);
  const saveBody = await saveResponse.json();
  const savedDraft = saveBody?.data;
  if (saveBody?.success !== true || savedDraft?.spec?.factory_id !== factoryA || savedDraft?.status !== 'draft') {
    throw new Error('Saved vision draft did not read back as a factory-A draft');
  }
  savedReceipts.push({
    method: 'POST', path: apiPath(saveResponse), status: saveResponse.status(), success: true,
    projectIdPresent: savedDraft.project_id != null, factoryId: savedDraft.spec.factory_id,
    statusValue: savedDraft.status,
  });
  await expect(page.getByTestId('vision-project')).toContainText(workstation, { timeout: 45_000 });
  await expect(page.getByText('本厂独立验收待完成', { exact: true })).toBeVisible();
  await waitForReceipt((item) => item.method === 'GET' && isVisionPath(item.path)
    && item.path.endsWith(`/vision-training/drafts/${encodeURIComponent(savedDraft.project_id)}/plan`), 'Missing plan readback after save');
  await page.screenshot({ path: resolve(out, '03-factory-a-saved.png'), fullPage: true });

  await page.reload({ waitUntil: 'domcontentloaded' });
  const reloadedA = await waitForVisionSetup(factoryA);
  await expect(reloadedA.locator('.vision-resume')).toBeVisible({ timeout: 45_000 });
  await reloadedA.locator('.vision-resume .el-select').click();
  const resumeDropdown = page.locator('.el-select-dropdown:visible').last();
  await resumeDropdown.getByText(`${workstation} · ${product} · ${dataVersion}`, { exact: true }).click();
  await expect(page.getByTestId('vision-project')).toContainText(workstation, { timeout: 45_000 });
  await page.screenshot({ path: resolve(out, '04-factory-a-reloaded.png'), fullPage: true });

  const setupB = await routeToFactory(factoryB, factoryBName);
  const templateB = await chooseGenericTemplate();
  const specializedLabel = templateA.specializedLabel;
  if (specializedRegistry && !templateA.options.includes(specializedLabel)) {
    throw new Error('Specialized registry was requested but factory-A did not expose the specialized template');
  }
  if (!specializedRegistry && templateA.options.includes(specializedLabel)) {
    throw new Error('Factory-A exposed a specialized template without a registered recipe');
  }
  if (templateB.options.includes(specializedLabel)) {
    throw new Error('Factory-B exposed the specialized template; specialized registry is factory-A scoped');
  }
  const bResume = setupB.locator('.vision-resume');
  if (await bResume.count()) {
    await bResume.locator('.el-select').click();
    const bDropdown = page.locator('.el-select-dropdown:visible').last();
    const bLabels = (await bDropdown.locator('.el-select-dropdown__item').allTextContents())
      .map((value) => value.trim()).filter(Boolean);
    if (bLabels.some((label) => label.includes(workstation) || label.includes(dataVersion))) {
      throw new Error('Factory-B draft list exposed factory-A project data');
    }
    await page.keyboard.press('Escape');
  }
  await expect(setupB).not.toContainText(workstation);
  await expect(setupB).not.toContainText(dataVersion);
  await page.screenshot({ path: resolve(out, '05-factory-b-isolated.png'), fullPage: true });

  const genericJobPosts = apiReceipts.filter((item) => item.method === 'POST'
    && isVisionPath(item.path) && item.path.endsWith('/vision-training/drafts/'
      + encodeURIComponent(savedDraft.project_id) + '/jobs'));
  if (genericJobPosts.length) throw new Error('Generic A/B scenario unexpectedly submitted a vision job');

  let specializedQueue = { status: 'skipped; no registry provided' };
  const specializedScreenshots = [];
  if (specializedRegistry) {
    const specializedSetup = await routeToFactory(factoryA, factoryAName);
    const newVersionButton = specializedSetup.locator('.vision-resume')
      .getByRole('button', { name: '新建任务版本', exact: true });
    if (await newVersionButton.count()) await newVersionButton.click();
    await expect(specializedSetup.getByTestId('vision-project')).toHaveCount(0);

    await chooseSpecializedTemplate(specializedLabel);
    await fillInput('vision-workstation', 'VISION-ENTRY-WS2');
    await addCreatedOption('vision-products', 'synthetic-label');
    await addCreatedOption('vision-packaging', 'tray');
    await chooseRecipe(specializedRecipeId);

    const specializedPreviewPromise = page.waitForResponse((response) => {
      return response.request().method() === 'POST' && isVisionPath(apiPath(response))
        && apiPath(response).endsWith('/vision-training/preview');
    });
    await page.getByRole('button', { name: '预览训练流程', exact: true }).click();
    const specializedPreview = await specializedPreviewPromise;
    if (!specializedPreview.ok()) throw new Error(`Specialized preview failed with HTTP ${specializedPreview.status()}`);
    const specializedPreviewBody = await specializedPreview.json();
    if (specializedPreviewBody?.data?.spec?.factory_id !== factoryA) {
      throw new Error('Specialized preview escaped factory A');
    }
    previewReceipts.push({
      method: 'POST', path: apiPath(specializedPreview), status: specializedPreview.status(),
      success: specializedPreviewBody?.success === true, previewIdPresent: specializedPreviewBody?.data?.preview_id != null,
      factoryId: specializedPreviewBody?.data?.spec?.factory_id, specialized: true,
    });

    const specializedSavePromise = page.waitForResponse((response) => {
      return response.request().method() === 'POST' && isVisionPath(apiPath(response))
        && apiPath(response).endsWith('/vision-training/drafts');
    });
    await page.getByRole('button', { name: '保存训练项目', exact: true }).click();
    const specializedSave = await specializedSavePromise;
    if (!specializedSave.ok()) throw new Error(`Specialized draft save failed with HTTP ${specializedSave.status()}`);
    const specializedSaveBody = await specializedSave.json();
    const specializedDraft = specializedSaveBody?.data;
    if (specializedSaveBody?.success !== true || specializedDraft?.spec?.factory_id !== factoryA
        || specializedDraft?.status !== 'draft') {
      throw new Error('Specialized draft did not read back as a factory-A draft');
    }
    savedReceipts.push({
      method: 'POST', path: apiPath(specializedSave), status: specializedSave.status(), success: true,
      projectIdPresent: specializedDraft.project_id != null, factoryId: specializedDraft.spec.factory_id,
      statusValue: specializedDraft.status, specialized: true,
    });
    await expect(specializedSetup.getByTestId('vision-project')).toContainText('VISION-ENTRY-WS2', { timeout: 45_000 });

    const queueResponsePromise = page.waitForResponse((response) => {
      return response.request().method() === 'POST' && isVisionPath(apiPath(response))
        && apiPath(response).endsWith(`/vision-training/drafts/${encodeURIComponent(specializedDraft.project_id)}/jobs`);
    });
    await page.getByRole('button', { name: '提交采集图片', exact: true }).click();
    const queueResponse = await queueResponsePromise;
    if (!queueResponse.ok()) throw new Error(`Specialized collect queue failed with HTTP ${queueResponse.status()}`);
    const queueBody = await queueResponse.json();
    const queuedJob = queueBody?.data;
    if (queueBody?.success !== true || queuedJob?.stage !== 'collect'
        || queuedJob?.identity?.factory_id !== factoryA
        || queuedJob?.identity?.project_id !== specializedDraft.project_id) {
      throw new Error('Specialized collect queue receipt is out of scope or malformed');
    }
    specializedQueue = {
      status: queuedJob.status,
      factoryId: queuedJob.identity.factory_id,
      projectId: queuedJob.identity.project_id,
      jobId: queuedJob.job_id,
      stage: queuedJob.stage,
    };
    await writeFile(resolve(out, 'specialized-queued.json'), JSON.stringify({
      taskId: 'VISION-ENTRY-UI-20261015', baseSha: '95e824cb1726a94959f75c2127183e9b44b5a048',
      factoryId: factoryA, projectId: specializedDraft.project_id, jobId: queuedJob.job_id,
      stage: queuedJob.stage, status: queuedJob.status,
    }, null, 2));
    specializedScreenshots.push('06-specialized-queued.png');
    await page.screenshot({ path: resolve(out, '06-specialized-queued.png'), fullPage: true });

    const refreshButton = specializedSetup.getByRole('button', { name: '刷新执行结果', exact: true });
    const collectRow = specializedSetup.locator('.vision-progress .el-table__row').filter({ hasText: '采集图片' });
    let collectCompleted = false;
    for (let second = 0; second < 120; second += 1) {
      await refreshButton.click();
      await page.waitForTimeout(1000);
      if ((await collectRow.innerText().catch(() => '')).includes('已完成')) {
        collectCompleted = true;
        break;
      }
    }
    if (!collectCompleted) throw new Error('Specialized collect did not reach 已完成 within 120 seconds');
    const datasetRow = specializedSetup.locator('.vision-progress .el-table__row').filter({ hasText: '整理数据集' });
    const trainRow = specializedSetup.locator('.vision-progress .el-table__row').filter({ hasText: '训练候选' });
    await expect(datasetRow).toContainText('待完成');
    await expect(trainRow).toContainText('待完成');
    await expect(specializedSetup.getByText('本厂独立验收待完成', { exact: true })).toBeVisible();
    await expect(specializedSetup.getByText('当前流程没有自动启用模型。', { exact: true })).toBeVisible();
    specializedQueue.collectCompleted = true;
    specializedQueue.datasetAutoStarted = false;
    specializedQueue.trainingAutoStarted = false;
    specializedQueue.independentAcceptancePending = true;
    specializedQueue.enabled = false;
    specializedScreenshots.push('07-specialized-collect-completed.png');
    await page.screenshot({ path: resolve(out, '07-specialized-collect-completed.png'), fullPage: true });
  }
  result = {
    taskId: 'VISION-ENTRY-UI-20261015',
    baseSha: '95e824cb1726a94959f75c2127183e9b44b5a048',
    target: base,
    result: 'PASS',
    platformLogin: loginReceipts[0],
    factoryA: {
      id: factoryA, template: templateA.genericLabel, specializedTemplateListed: templateA.options.includes(templateA.specializedLabel),
      projectIdPresent: savedDraft.project_id != null, savedStatus: savedDraft.status,
      readback: true,
    },
    factoryB: {
      id: factoryB, template: templateB.genericLabel, factoryADraftVisible: false,
      specializedTemplateListed: templateB.options.includes(templateB.specializedLabel),
    },
    specializedQueue,
    trainingExecuted: false,
    productionWrites: 0,
    previewReceipts,
    savedReceipts,
    apiReceipts,
    consoleErrors,
    screenshots: [
      '00-platform-login.png', '01-factory-a-form.png', '02-factory-a-preview.png',
      '03-factory-a-saved.png', '04-factory-a-reloaded.png', '05-factory-b-isolated.png',
      ...specializedScreenshots,
    ],
  };
  if (consoleErrors.length) throw new Error(`Browser console errors: ${consoleErrors.join('; ')}`);
  await writeFile(resolve(out, 'platform-result.json'), JSON.stringify(result, null, 2));
} catch (error) {
  await page.screenshot({ path: resolve(out, 'platform-failure.png'), fullPage: true }).catch(() => {});
  const failure = {
    taskId: 'VISION-ENTRY-UI-20261015', baseSha: '95e824cb1726a94959f75c2127183e9b44b5a048',
    result: 'FAIL', error: String(error), apiReceipts, loginReceipts, savedReceipts, previewReceipts, consoleErrors,
  };
  await writeFile(resolve(out, 'platform-failure.json'), JSON.stringify(failure, null, 2));
  throw error;
} finally {
  await browser.close();
}
