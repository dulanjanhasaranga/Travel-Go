#!/usr/bin/env node
'use strict';

// Local, disposable ReferenceDesignBrowserPreview only. No personal browser
// profile, working database, provider key or production sign-in is used.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '../target/mobile-performance-tools/node_modules/playwright');

const ROOT = path.resolve(__dirname, '..');
const BASE = 'http://127.0.0.1:8081';
const OUTPUT = path.join(ROOT, 'target/mobile-performance-20261108');
const ROUTES = ['/', '/destinations', '/packages', '/visa-info', '/about', '/contact', '/faq'];
const WIDTHS = [320, 390, 600, 768, 900, 1199, 1200, 1440];
const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),summary,[tabindex]:not([tabindex="-1"])';
const results = [];
const diagnostics = { consoleErrors: [], pageErrors: [], brokenAssets: [], externalFontFailures: [], requestFailures: [] };

function safeUrl(value) {
  try { const url = new URL(value); return url.origin + url.pathname; }
  catch { return String(value).slice(0, 200); }
}
function isFontUrl(value) { return /https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com)\//.test(value); }

function observe(page, name) {
  page.on('console', message => {
    if (message.type() !== 'error') return;
    const location = message.location().url || '';
    const item = { name, message: message.text().slice(0, 500), url: safeUrl(location) };
    if (isFontUrl(location) && /ERR_|Failed to load resource/.test(message.text())) diagnostics.externalFontFailures.push(item);
    else diagnostics.consoleErrors.push(item);
  });
  page.on('pageerror', error => diagnostics.pageErrors.push({ name, message: error.message.slice(0, 600) }));
  page.on('response', response => {
    if (response.url().endsWith('.woff2') && new URL(response.url()).origin === BASE && ![200, 304].includes(response.status())) {
      diagnostics.brokenAssets.push({ name, status: response.status(), url: safeUrl(response.url()) });
    }
    if (response.status() < 400 || new URL(response.url()).origin !== BASE) return;
    diagnostics.brokenAssets.push({ name, status: response.status(), url: safeUrl(response.url()) });
  });
  page.on('requestfailed', request => {
    const item = { name, url: safeUrl(request.url()), error: request.failure()?.errorText || 'failed' };
    if (isFontUrl(request.url())) diagnostics.externalFontFailures.push(item);
    // Normal link navigation can cancel lazy image requests; it is not a broken asset.
    else if (!/ERR_ABORTED|NS_BINDING_ABORTED/.test(item.error)) diagnostics.requestFailures.push(item);
  });
}

async function check(name, callback) {
  const start = Date.now();
  try { await callback(); results.push({ name, passed: true, durationMs: Date.now() - start }); }
  catch (error) { results.push({ name, passed: false, durationMs: Date.now() - start, error: error.message }); }
}

async function fixtureGuard(context, page) {
  const response = await page.goto(BASE + '/auth/login', { waitUntil: 'domcontentloaded' });
  assert.equal(new URL(page.url()).origin, BASE, 'Refusing a redirect outside the isolated local preview');
  assert.equal(response.status(), 200);
  assert((await context.cookies(BASE)).some(cookie => cookie.name === 'TRAVELGO_REFERENCE_PREVIEW'),
    'Refusing browser checks: expected the isolated H2 preview session cookie');
}

async function loaded(page, route = '/') {
  const response = await page.goto(BASE + route, { waitUntil: 'domcontentloaded' });
  assert.equal(response.status(), 200, route + ' must remain reachable');
  assert.equal(new URL(page.url()).origin, BASE);
  await page.waitForFunction(() => document.documentElement.classList.contains('nav-ready'));
  await page.evaluate(() => Promise.race([document.fonts.ready, new Promise(resolve => setTimeout(resolve, 5000))]));
  assert(await page.evaluate(() => ['Inter', 'Plus Jakarta Sans'].every(family =>
    [...document.fonts].some(face => face.family.replace(/["']/g, '') === family && face.status === 'loaded'))),
  'Both original brand typefaces must load successfully');
}

async function open(page) {
  const toggle = page.locator('.nav-toggle');
  if (await page.evaluate(() => matchMedia('(pointer: coarse)').matches)) await toggle.tap();
  else await toggle.click();
  await page.waitForFunction(() => {
    const panel = document.getElementById('site-navigation-panel');
    return panel?.getAttribute('aria-modal') === 'true' &&
      Math.abs(new DOMMatrixReadOnly(getComputedStyle(panel).transform).m41) < 0.5;
  });
}

async function closed(page) {
  await page.waitForFunction(() => {
    const panel = document.getElementById('site-navigation-panel');
    return document.querySelector('.nav-toggle')?.getAttribute('aria-expanded') === 'false' &&
      getComputedStyle(panel).visibility === 'hidden';
  });
  assert.equal(await page.locator('#site-navigation-panel').getAttribute('aria-hidden'), 'true');
  assert(await page.locator('#site-navigation-panel').evaluate(element => element.inert));
  assert(await page.locator('.nav-backdrop').evaluate(element => element.hidden));
  assert.equal(await page.evaluate(() => document.body.style.position), '');
}

async function assertNoOverflow(page) {
  const details = await page.evaluate(() => ({
    viewport: innerWidth,
    document: document.documentElement.scrollWidth,
    offenders: [...document.querySelectorAll('body *')].filter(element => {
      const bounds = element.getBoundingClientRect();
      return getComputedStyle(element).visibility !== 'hidden' && bounds.width &&
        (bounds.right > innerWidth + 1 || bounds.left < -1);
    }).slice(0, 8).map(element => ({ tag: element.tagName, class: element.className?.toString().slice(0, 90) }))
  }));
  assert(details.document <= details.viewport + 1, 'Horizontal overflow: ' + JSON.stringify(details));
}

async function assertOpenState(page) {
  const panel = page.locator('#site-navigation-panel');
  assert.equal(await page.locator('.nav-toggle').getAttribute('aria-expanded'), 'true');
  assert.equal(await panel.getAttribute('role'), 'dialog');
  assert.equal(await panel.getAttribute('aria-modal'), 'true');
  assert.equal(await panel.getAttribute('aria-labelledby'), 'site-navigation-title');
  assert.equal(await panel.getAttribute('aria-hidden'), null);
  assert.equal(await panel.evaluate(element => element.inert), false);
  assert.equal(await page.locator('.nav-backdrop').evaluate(element => element.hidden), false);
  assert.equal(await page.evaluate(() => document.body.style.position), 'fixed');
  assert(await page.locator('main').evaluate(element => element.inert || !!element.closest('[inert]')));
  const bounds = await panel.boundingBox();
  const viewport = page.viewportSize();
  assert(bounds.x >= -1 && bounds.x + bounds.width <= viewport.width + 1);
  assert(bounds.height <= viewport.height + 1 && bounds.y >= -1);
  await assertNoOverflow(page);
}

async function focusBoundaries(page) {
  assert(await page.locator('.nav-close').evaluate(element => element === document.activeElement));
  await page.locator('#site-navigation-panel').evaluate((panel, selector) => {
    const items = [...panel.querySelectorAll(selector)].filter(element => element.getClientRects().length && !element.closest('[inert]'));
    items.at(-1).focus();
  }, FOCUSABLE);
  await page.keyboard.press('Tab');
  assert(await page.locator('#site-navigation-panel').evaluate((panel, selector) => {
    const items = [...panel.querySelectorAll(selector)].filter(element => element.getClientRects().length && !element.closest('[inert]'));
    return document.activeElement === items[0];
  }, FOCUSABLE), 'Tab must wrap from the last item to the first');
  await page.keyboard.press('Shift+Tab');
  assert(await page.locator('#site-navigation-panel').evaluate((panel, selector) => {
    const items = [...panel.querySelectorAll(selector)].filter(element => element.getClientRects().length && !element.closest('[inert]'));
    return document.activeElement === items.at(-1);
  }, FOCUSABLE), 'Shift+Tab must wrap to the last item');
  await page.locator('.brand-logo').evaluate(element => element.focus());
  assert(await page.locator('#site-navigation-panel').evaluate(panel => panel.contains(document.activeElement)),
    'Focus must remain in the modal drawer');
}

async function screenshot(page, filename) {
  await page.screenshot({ path: path.join(OUTPUT, filename), fullPage: false, animations: 'disabled' });
}

async function viewportCase(browser, width) {
  const context = await browser.newContext({ viewport: { width, height: width >= 1200 ? 900 : 844 }, deviceScaleFactor: 1, hasTouch: width <= 600, isMobile: width <= 600 });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  observe(page, `width-${width}`);
  try {
    await fixtureGuard(context, page);
    await loaded(page);
    await assertNoOverflow(page);
    const header = await page.locator('header.navbar').boundingBox();
    assert(header.height <= 110, `Header should remain compact at ${width}px; got ${header.height}px`);
    assert.deepEqual(await page.locator('#primary-navigation a').evaluateAll(links => links.map(link => new URL(link.href).pathname)), ROUTES);
    if (width >= 1200) {
      assert.equal(await page.locator('.nav-toggle').isVisible(), false);
      assert(await page.locator('#site-navigation-panel').isVisible());
      assert.equal(await page.locator('#site-navigation-panel').evaluate(panel => panel.inert), false);
      assert.equal(await page.locator('#site-navigation-panel').getAttribute('role'), null);
      assert(await page.locator('.nav-auth-actions a').first().isVisible());
      if (width === 1440) await screenshot(page, 'navigation-1440-desktop.png');
      return;
    }
    assert(await page.locator('.nav-toggle').isVisible());
    const target = await page.locator('.nav-toggle').boundingBox();
    assert(target.height >= 44 && target.width >= 44, 'Touch target must be at least 44px');
    await closed(page);
    if (width === 390) await screenshot(page, 'navigation-390-closed.png');
    await page.evaluate(() => scrollTo(0, 400));
    const beforeScroll = await page.evaluate(() => scrollY);
    await open(page);
    await assertOpenState(page);
    await focusBoundaries(page);
    if (width === 390 || width === 768) await screenshot(page, `navigation-${width}-open.png`);
    await page.keyboard.press('Escape');
    await closed(page);
    assert(await page.locator('.nav-toggle').evaluate(element => element === document.activeElement));
    assert(Math.abs(await page.evaluate(() => scrollY) - beforeScroll) <= 2, 'Closing must preserve the previous scroll position');
    await open(page);
    await page.locator('.nav-close').click();
    await closed(page);
    if (width > 440) {
      await open(page);
      await page.mouse.click(10, 150);
      await closed(page);
    }
    // Cancel only this test's native navigation to inspect the close handler itself.
    await open(page);
    const link = page.locator('#primary-navigation a[href="/packages"]');
    await link.evaluate(element => element.addEventListener('click', event => event.preventDefault(), { once: true }));
    await link.click();
    await closed(page);
    await open(page);
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForFunction(() => document.querySelector('.nav-toggle').getAttribute('aria-expanded') === 'false');
    assert.equal(await page.locator('#site-navigation-panel').evaluate(panel => panel.inert), false);
    assert.equal(await page.locator('#site-navigation-panel').getAttribute('aria-hidden'), null);
    assert.equal(await page.locator('#site-navigation-panel').getAttribute('role'), null);
    assert.equal(await page.locator('main').evaluate(main => main.inert || !!main.closest('[inert]')), false);
    assert.equal(await page.evaluate(() => document.body.style.position), '');
    await assertNoOverflow(page);
  } finally { await context.close(); }
}

async function routeCase(browser) {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  observe(page, 'all-navigation-routes');
  try {
    await fixtureGuard(context, page);
    await loaded(page);
    for (const route of ROUTES) {
      await open(page);
      await Promise.all([
        page.waitForURL(BASE + route, { waitUntil: 'domcontentloaded' }),
        page.locator('#primary-navigation a').filter({ hasText: new RegExp('^' + ({ '/': 'Home', '/destinations': 'Destinations', '/packages': 'Tour Packages', '/visa-info': 'Visa Guide', '/about': 'About', '/contact': 'Contact', '/faq': 'FAQ' }[route]) + '$') }).click()
      ]);
      await page.waitForFunction(() => document.documentElement.classList.contains('nav-ready'));
      await closed(page);
      const current = page.locator('#primary-navigation a[aria-current="page"]');
      assert.equal(await current.count(), 1);
      assert.equal(new URL(await current.getAttribute('href'), BASE).pathname, route);
    }
  } finally { await context.close(); }
}

async function noJavascriptCase(browser) {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 320, height: 844 } });
  const page = await context.newPage();
  observe(page, 'no-javascript');
  try {
    await fixtureGuard(context, page);
    assert.equal((await page.goto(BASE, { waitUntil: 'load' })).status(), 200);
    for (const link of await page.locator('#primary-navigation a').all()) assert(await link.isVisible());
    for (const link of await page.locator('.nav-auth-actions a').all()) assert(await link.isVisible());
    assert.equal(await page.locator('.nav-toggle').isVisible(), false);
    await assertNoOverflow(page);
  } finally { await context.close(); }
}

async function reducedMotionCase(browser) {
  const context = await browser.newContext({ reducedMotion: 'reduce', viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  observe(page, 'reduced-motion');
  try {
    await fixtureGuard(context, page);
    await loaded(page);
    await open(page);
    const duration = await page.locator('#site-navigation-panel').evaluate(element => getComputedStyle(element).transitionDuration);
    assert(duration.split(',').every(value => parseFloat(value) <= 0.01), 'Reduced motion must remove the drawer transition: ' + duration);
    await page.keyboard.press('Escape');
    await closed(page);
  } finally { await context.close(); }
}

async function customerAccountCase(browser) {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  const page = await context.newPage();
  observe(page, 'synthetic-customer-account');
  try {
    await fixtureGuard(context, page);
    // These are public synthetic fixture values, usable only in the disposable H2 preview.
    await page.locator('#email').fill('customer@example.invalid');
    await page.locator('#password').fill('Preview-only-123!');
    // Ordinary customer sign-in deliberately returns to the public homepage.
    await Promise.all([page.waitForURL(BASE + '/', { waitUntil: 'domcontentloaded' }), page.locator('#loginForm button[type="submit"]').click()]);
    await loaded(page, '/customer/dashboard');
    await page.waitForFunction(() => document.documentElement.classList.contains('nav-ready'));
    await open(page);
    await page.locator('.account-menu > summary').tap();
    assert(await page.locator('.account-menu').evaluate(element => element.open));
    assert.equal(await page.locator('.account-menu-links a').count(), 6);
    assert.equal(await page.locator('form[action="/auth/logout"]').count(), 1);
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('.account-menu').evaluate(element => element.open), false);
    assert.equal(await page.locator('.nav-toggle').getAttribute('aria-expanded'), 'true', 'Nested Escape closes the account menu first');
    await page.locator('.notification-center > summary').tap();
    assert(await page.locator('.notification-center').evaluate(element => element.open));
    await page.locator('.account-menu > summary').tap();
    await page.waitForFunction(() => !document.querySelector('.notification-center').open);
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');
    await closed(page);
  } finally { await context.close(); }
}

(async () => {
  await fs.mkdir(OUTPUT, { recursive: true });
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    for (const width of WIDTHS) await check(`viewport-${width}`, () => viewportCase(browser, width));
    await check('all-seven-public-routes', () => routeCase(browser));
    await check('no-javascript-fallback', () => noJavascriptCase(browser));
    await check('reduced-motion', () => reducedMotionCase(browser));
    await check('synthetic-customer-dropdowns', () => customerAccountCase(browser));
    await check('no-browser-errors-or-broken-local-assets', async () => {
      assert.deepEqual(diagnostics.pageErrors, []);
      assert.deepEqual(diagnostics.consoleErrors, []);
      assert.deepEqual(diagnostics.brokenAssets, []);
      assert.deepEqual(diagnostics.requestFailures.filter(item => item.url.startsWith(BASE)), []);
    });
  } finally { await browser.close(); }
  const report = { generatedAt: new Date().toISOString(), base: BASE, fixture: 'guarded-disposable-H2', browser: 'Microsoft Edge, Playwright headless; no personal profile', results, diagnostics };
  await fs.writeFile(path.join(OUTPUT, 'navigation-browser-tests.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ passed: results.filter(result => result.passed).length, total: results.length, failures: results.filter(result => !result.passed), externalFontFailures: diagnostics.externalFontFailures.length, report: path.join(OUTPUT, 'navigation-browser-tests.json') }, null, 2));
  if (results.some(result => !result.passed)) process.exitCode = 1;
})().catch(error => { console.error(error.message); process.exitCode = 1; });
