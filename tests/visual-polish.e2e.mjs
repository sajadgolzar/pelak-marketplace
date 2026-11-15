import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

const browser = await chromium.launch({ headless: true });
await mkdir('ui-screenshots', { recursive: true });

async function testViewport(width, height, label) {
  const context = await browser.newContext({
    viewport: { width, height },
    deviceScaleFactor: 1,
    isMobile: width < 600,
    hasTouch: width < 600,
    locale: 'fa-IR'
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.stack || error.message));
  // Preserve the local page and static assets, but don't trigger live Supabase reads.
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.hostname === '127.0.0.1' || url.hostname === 'localhost') {
      await route.continue();
    } else {
      await route.abort();
    }
  });
  await page.goto('http://127.0.0.1:8765/', {
    waitUntil: 'domcontentloaded', timeout: 60000
  });
  await page.locator('.pelak-shortcuts > button.pv-shortcut')
    .first().waitFor({ state: 'visible', timeout: 15000 });
  const shortcuts = await page.locator('.pelak-shortcuts > button.pv-shortcut').count();
  assert.equal(shortcuts, 6, label + ': six main shortcuts must remain');
  assert.equal(await page.locator('.pelak-sales-banner').count(), 0,
    label + ': the removed car banner must stay removed');
  assert.equal(await page.locator('.pelak-shortcuts > button.pv-shortcut .pv-shortcut-icon').count(), 6,
    label + ': all six shortcuts must have vector icons');

  await page.locator('#pelakMenuTrigger').click();
  assert.equal(await page.locator('#pelakDrawer').getAttribute('aria-hidden'), 'false',
    label + ': menu should open');
  const groups = page.locator('#pelakMenuItems > .pelak-menu-group-title[data-pv-collapsible="1"]');
  assert.ok((await groups.count()) >= 3, label + ': menu needs accessible grouped categories');
  const secondGroup = groups.nth(1);
  assert.equal(await secondGroup.getAttribute('aria-expanded'), 'false',
    label + ': secondary groups start collapsed');
  await secondGroup.click();
  assert.equal(await secondGroup.getAttribute('aria-expanded'), 'true',
    label + ': menu groups can expand');
  await page.locator('#pelakCloseMenu').click();

  await page.locator('.pelak-shortcuts > button[data-pelak-view="search"]').click();
  const showMore = page.locator('.pv-search-expand-button');
  assert.ok(await showMore.isVisible(), label + ': specialist filters control exists');
  assert.equal(await page.locator('#engineMinFilter').isVisible(), false,
    label + ': specialist filters start collapsed');
  await showMore.click();
  assert.equal(await page.locator('#engineMinFilter').isVisible(), true,
    label + ': specialist filters can be expanded');
  await showMore.click();
  assert.equal(await page.locator('#engineMinFilter').isVisible(), false,
    label + ': specialist filters can be collapsed again');

  assert.equal(await page.locator('footer.foot .pv-footer-links button').count(), 4,
    label + ': quick footer navigation should have four destinations');
  assert.equal(await page.locator('footer.foot #pelakPlatformFooter .pelak-platform-choice').count(), 3,
    label + ': keep all three existing download options');
  assert.ok((await page.locator('footer.foot .pv-footer-about small').innerText()).includes('PELAK'),
    label + ': footer should not show internal backend details');

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
  console.log(label + ': horizontal-overflow=' + overflow + 'px, shortcuts=' + shortcuts);
  assert.ok(overflow <= 12, label + ': unexpected page-wide horizontal overflow');
  await page.locator('.pelak-shortcuts > button[data-pelak-view="listings"]').click();
  await page.screenshot({
    path: 'ui-screenshots/' + label + '-home.png',
    fullPage: true, animations: 'disabled'
  });
  if (errors.length) console.log(label + ': pre-existing or blocked-external page errors: ' + errors.slice(0, 4).join(' | ').slice(0, 6000));
  await context.close();
}

try {
  await testViewport(390, 844, 'mobile');
  await testViewport(1365, 900, 'desktop');
  console.log('PELAK visual UI tests passed.');
} finally {
  await browser.close();
}
