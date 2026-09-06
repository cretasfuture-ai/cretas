/**
 * J9 员工工序片段 Web 主管视图 — super_admin @post-deploy
 *
 * P1-1 Web side: Workshop supervisor views active employee process segments.
 * The RN scan (clockin/clockout) is deferred (hardware-gated). This spec covers:
 *
 *   Test 1: Production management page (/production/batches) — accessible + shows table
 *   (Test 2 removed 2026-09-06: the employee process segments API was deleted with the entity —
 *    the table never existed in any database and the RN client was retired on 2026-08-15, PR #660)
 *   Test 3: Production approval page (/production/approval) — supervisor can view work reports
 *
 * Auth: super_admin (factory_super_admin — has full permissions; workshop_supervisor is MOBILE_ONLY
 *       per guards.ts, cannot log in to web-admin).
 *
 * Factory: F_E2E_TEST
 */

import { test, expect } from '@playwright/test';
import { setupAuthBeforeAll, restoreAuth } from '../helpers/auth-cache';
import { expectNoErrors } from '../helpers/assertions';
import { S } from '../helpers/selectors';

// ─── Constants ────────────────────────────────────────────────────────────────

const FACTORY_ID = 'F_E2E_TEST';

// ─── Suite ────────────────────────────────────────────────────────────────────

test.describe('J9 员工工序片段 Web 主管视图 @post-deploy', () => {

  test.beforeAll(async ({ browser }) => {
    await setupAuthBeforeAll(browser, 'super_admin');
  });

  test.beforeEach(async ({ page, context }) => {
    await restoreAuth(context, page, 'super_admin');
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // Test 1: Production management page accessible
  // ═══════════════════════════════════════════════════════════════════════════

  test('生产管理页 /production/batches 可访问 + 显示批次表格', async ({ page }) => {
    await page.goto('/production/batches');
    await page.waitForLoadState('networkidle');

    // Should NOT redirect to /login
    expect(page.url()).not.toMatch(/\/login/);
    // Should NOT redirect to /mobile-only (super_admin is not mobile-only)
    expect(page.url()).not.toMatch(/\/mobile-only/);

    // Table container should appear (may be empty if no batches seeded)
    const table = page.locator(S.table.root).first();
    await expect(table).toBeVisible({ timeout: 15_000 });

    await expectNoErrors(page);
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // Test 3: Production approval page (supervisor work report view)
  // ═══════════════════════════════════════════════════════════════════════════

  test('报工审批页 /production/approval 可访问 + 主管视图渲染', async ({ page }) => {
    await page.goto('/production/approval');
    await page.waitForLoadState('networkidle');

    // Should NOT redirect to /login or /mobile-only
    expect(page.url()).not.toMatch(/\/login/);
    expect(page.url()).not.toMatch(/\/mobile-only/);

    // The page wraps content in an el-card
    const card = page.locator('.el-card').first();
    await expect(card).toBeVisible({ timeout: 15_000 });

    // Page header "报工审批" should be visible
    const heading = page.locator('text=报工审批').first();
    await expect(heading).toBeVisible({ timeout: 10_000 });

    // Table should exist (may be empty — no pending approvals in fresh seed)
    const table = page.locator(S.table.root).first();
    await expect(table).toBeVisible({ timeout: 10_000 });

    await expectNoErrors(page);
  });
});
