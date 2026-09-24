// Drives the demo page in a real browser through every test card and checks what it shows.
// Usage: node scripts/demo-e2e.js [base-url] [screenshot-dir]   (needs the playwright package + Chromium)
// Expects a full stack: the retry job running and Kafka consumers enabled.
const { chromium } = require('playwright');
const BASE = process.argv[2] || 'http://localhost:8080';
const SHOTS = process.argv[3];
const expectText = async (page, sel, re, timeout = 15000) => {
  await page.waitForFunction(([s, src]) => new RegExp(src).test(document.querySelector(s)?.textContent || ''), [sel, re.source], { timeout });
};
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const problems = [];
  page.on('console', m => { if (m.type() === 'error') problems.push('console: ' + m.text()); });
  page.on('pageerror', e => problems.push('pageerror: ' + e.message));
  const step = (s) => console.log('✓ ' + s);

  await page.goto(BASE + '/');
  await page.waitForFunction(() => !document.querySelector('#pay-button').disabled, null, { timeout: 15000 });
  step('session created (demo merchant + customer)');

  const payWith = async (token, amount = '1000') => {
    await page.fill('#amount', amount);
    await page.check(`#card-${token}`);
    await page.click('#pay-button');
  };

  await payWith('tok_success');
  await expectText(page, '#status-note', /Charged/);
  await page.waitForFunction(() => document.querySelectorAll('#ledger tbody tr').length === 2);
  step('tok_success -> SUCCESS, 2 ledger entries');
  const firstId = await page.textContent('#details dd span.mono');

  await page.click('#replay-button');
  await expectText(page, '#action-note', /same payment/);
  step('replay with same Idempotency-Key -> same payment ' + firstId);

  await page.click('#refund-button');
  await expectText(page, '#status-note', /Refunded/);
  await page.waitForFunction(() => document.querySelectorAll('#ledger tbody tr').length === 4);
  step('refund -> REFUNDED, reversing ledger transaction (4 entries)');

  await payWith('tok_timeout_after_charge');
  await expectText(page, '#status-note', /timed out/);
  step('tok_timeout_after_charge -> PENDING with timeout explanation');
  await expectText(page, '#status-note', /Charged/, 30000);
  const attempts = await page.textContent('#details dd:nth-of-type(3)');
  step('retry job resolved it -> SUCCESS after ' + attempts + ' attempts');

  await payWith('tok_decline');
  await expectText(page, '#status-note', /CARD_DECLINED/);
  step('tok_decline -> FAILED CARD_DECLINED');

  await payWith('tok_refund_decline');
  await expectText(page, '#status-note', /Charged/);
  await page.click('#refund-button');
  await expectText(page, '#action-note', /Refund declined/);
  await expectText(page, '#status-note', /Charged/);
  step('tok_refund_decline -> refund declined, payment back to SUCCESS');

  await payWith('tok_blocked');
  await expectText(page, '#status-note', /FRAUD_SUSPECTED/);
  step('tok_blocked -> FAILED FRAUD_SUSPECTED');

  await payWith('tok_success', '300000');
  await expectText(page, '#status-note', /FRAUD_SUSPECTED/);
  step('₹3,00,000 -> blocked by amount limit');

  await page.waitForFunction(() => document.querySelectorAll('#notifications li').length >= 4, null, { timeout: 30000 });
  await expectText(page, '#stats', /Succeeded3/, 30000);
  step('Kafka consumers caught up: ' + await page.$$eval('#notifications li', l => l.length) + ' notifications, stats: '
      + (await page.textContent('#stats')).replace(/\s+/g, ' '));

  const rows = await page.$$eval('#history tbody tr', r => r.length);
  step(`history shows ${rows} payments`);
  await page.click('#history tbody tr:last-child');
  await expectText(page, '#status-note', /Refunded/);
  step('clicking a history row reopens that payment');

  const leaked = await page.evaluate(() => document.body.innerHTML.includes('sk_test_'));
  if (leaked) problems.push('API key rendered into the page');

  const shot = async (name) => { if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true }); };
  await shot('desktop-light');
  await page.emulateMedia({ colorScheme: 'dark' });
  await shot('desktop-dark');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ colorScheme: 'light' });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  if (overflow) problems.push('horizontal scroll at phone width');
  await shot('mobile');

  await browser.close();
  if (problems.length) { console.log('PROBLEMS:\n' + problems.join('\n')); process.exit(1); }
  console.log('ALL CHECKS PASSED, no console errors');
})().catch(e => { console.error('FAILED: ' + e.message); process.exit(1); });
