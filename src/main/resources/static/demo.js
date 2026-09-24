// PayCore demo page. Plain JavaScript against the public REST API; no build step.
// All API data is written with textContent (never innerHTML), so nothing from the API can inject markup.
'use strict';

const CARDS = [
  { token: 'tok_success', name: 'Succeeds', desc: 'The processor approves the charge.' },
  { token: 'tok_decline', name: 'Declined', desc: 'The bank declines it. No ledger entries.' },
  { token: 'tok_timeout', name: 'Timeout, then success',
    desc: 'The first call never arrives. It stays PENDING until the retry job succeeds (~5s).' },
  { token: 'tok_timeout_after_charge', name: 'Charged, response lost',
    desc: 'The card is charged but the answer is lost. The retry job asks the processor instead of charging again.' },
  { token: 'tok_timeout_always', name: 'Processor unreachable',
    desc: 'Retries with backoff, then fails once the processor confirms it never charged (about a minute).' },
  { token: 'tok_refund_decline', name: 'Refund declined', desc: 'Payment succeeds; refunds are declined.' },
  { token: 'tok_blocked', name: 'Fraud block', desc: 'The card is on the deny list: blocked before the processor.' },
];

const MAIN_PATH = ['CREATED', 'PENDING', 'SUCCESS', 'REFUND_PENDING', 'REFUNDED'];
const TERMINAL_OFF_PATH = ['FAILED', 'CANCELLED'];
const OK = new Set(['SUCCESS', 'SUCCEEDED', 'REFUNDED']);
const BAD = new Set(['FAILED', 'CANCELLED']);
const WAIT = new Set(['CREATED', 'PENDING', 'REFUND_PENDING']);

const SESSION_KEY = 'paycore-demo-session';
const state = {
  session: null,          // { apiKey, merchantId, customerId }
  methods: {},            // token -> payment method id
  current: null,          // payment currently shown
  lastRequest: null,      // { key, body, paymentId } of the latest POST /payments, for the replay button
  pollTimer: null,
};

// ---------- small helpers ----------

const $ = (id) => document.getElementById(id);

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v);
  }
  for (const child of children) {
    if (child != null) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function money(minor, currency = 'INR') {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(minor / 100);
}

function tone(status) {
  if (OK.has(status)) return 'ok';
  if (BAD.has(status)) return 'bad';
  if (WAIT.has(status)) return 'wait';
  return '';
}

function time(iso) {
  return iso ? new Date(iso).toLocaleTimeString() : '';
}

function newKey(prefix) {
  const random = crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2) + Date.now();
  return `${prefix}-${random}`;
}

// ---------- API ----------

async function api(method, path, { body, idempotencyKey } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (state.session) headers.Authorization = `Bearer ${state.session.apiKey}`;
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  const replayed = res.headers.get('Idempotent-Replayed') === 'true';
  if (method !== 'GET') logCall(method, path, res.status, replayed, idempotencyKey);
  return { status: res.status, ok: res.ok, data, replayed };
}

function logCall(method, path, status, replayed, key) {
  const flags = [];
  if (key) flags.push(`Idempotency-Key: ${key}`);
  const item = el('li', {},
    el('span', { class: 'method', text: method }),
    el('span', { class: 'path mono' }, path, flags.length ? el('div', { class: 'hint', text: flags.join(' ') }) : null),
    el('span', {},
      el('span', { class: `pill ${status < 300 ? 'ok' : status < 500 ? 'wait' : 'bad'}`, text: String(status) }),
      replayed ? el('div', { class: 'flag', text: 'replayed' }) : null));
  $('log').prepend(item);
}

function errorText(res) {
  return res.data?.error?.message || `HTTP ${res.status}`;
}

// ---------- session: one demo merchant + customer per browser tab ----------

async function ensureSession() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(SESSION_KEY) || 'null');
    if (saved) {
      state.session = saved;
      const check = await api('GET', `/api/v1/merchants/${saved.merchantId}`);
      if (check.ok) return;
    }
  } catch { /* storage unavailable or stale session: create a new one */ }

  state.session = null;
  const merchant = await api('POST', '/api/v1/merchants', {
    body: { name: 'Demo Store', email: `demo-${newKey('m')}@example.com` },
  });
  if (!merchant.ok) throw new Error(`Could not create a demo merchant: ${errorText(merchant)}`);
  state.session = { apiKey: merchant.data.apiKey, merchantId: merchant.data.merchant.id };

  const customer = await api('POST', '/api/v1/customers', { body: { name: 'Asha', email: 'asha@example.com' } });
  if (!customer.ok) throw new Error(`Could not create a demo customer: ${errorText(customer)}`);
  state.session.customerId = customer.data.id;
  try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(state.session)); } catch { /* fine */ }
}

async function paymentMethodFor(token) {
  if (state.methods[token]) return state.methods[token];
  const res = await api('POST', '/api/v1/payment-methods', {
    body: { customerId: state.session.customerId, type: 'CARD', token, lastFour: '4242' },
  });
  if (!res.ok) throw new Error(errorText(res));
  state.methods[token] = res.data.id;
  return res.data.id;
}

// ---------- actions ----------

async function pay(event) {
  event.preventDefault();
  const rupees = Number($('amount').value);
  if (!Number.isFinite(rupees) || rupees < 1) return;
  const token = new FormData($('pay-form')).get('card');
  setBusy(true);
  try {
    const body = {
      amount: Math.round(rupees * 100),
      currency: 'INR',
      customerId: state.session.customerId,
      paymentMethodId: await paymentMethodFor(token),
    };
    const key = newKey('order');
    state.lastRequest = { key, body };
    const res = await api('POST', '/api/v1/payments', { body, idempotencyKey: key });
    if (!res.ok) {
      note('action-note', `Payment rejected: ${errorText(res)}`, 'bad');
      return;
    }
    state.lastRequest.paymentId = res.data.id;
    note('action-note', '');
    await show(res.data.id);
  } catch (e) {
    note('action-note', e.message, 'bad');
  } finally {
    setBusy(false);
  }
}

async function replay() {
  if (!state.lastRequest) return;
  const { key, body } = state.lastRequest;
  const res = await api('POST', '/api/v1/payments', { body, idempotencyKey: key });
  if (res.ok && res.data.id === state.current?.id) {
    note('action-note', `Same Idempotency-Key, same payment ${res.data.id}: the original response was replayed `
      + 'and the card was not charged again.', 'ok');
  } else if (res.ok) {
    note('action-note', `Replayed payment ${res.data.id}.`, 'ok');
  } else {
    note('action-note', errorText(res), 'bad');
  }
}

async function refund() {
  const p = state.current;
  if (!p) return;
  const res = await api('POST', `/api/v1/payments/${p.id}/refund`, {
    body: { reason: 'Demo refund' }, idempotencyKey: newKey('refund'),
  });
  if (!res.ok) {
    note('action-note', `Refund rejected: ${errorText(res)}`, 'bad');
  } else if (res.data.status === 'FAILED') {
    note('action-note', `Refund declined (${res.data.failureCode}). The payment is still charged, so it went back `
      + 'to SUCCESS.', 'bad');
  } else {
    note('action-note', 'Refund processed: the ledger now has a reversing transaction.', 'ok');
  }
  await refresh();
}

// ---------- rendering ----------

async function show(paymentId) {
  const res = await api('GET', `/api/v1/payments/${paymentId}`);
  if (!res.ok) return;
  state.current = res.data;
  $('payment-empty').hidden = true;
  $('payment').hidden = false;
  await renderAll();
  schedulePoll();
}

async function refresh() {
  if (!state.current) return;
  const res = await api('GET', `/api/v1/payments/${state.current.id}`);
  if (res.ok) state.current = res.data;
  await renderAll();
}

async function renderAll() {
  const p = state.current;
  const [ledger, audit] = await Promise.all([
    api('GET', `/api/v1/payments/${p.id}/ledger`),
    api('GET', `/api/v1/payments/${p.id}/audit-logs`),
  ]);
  renderPayment(p, audit.ok ? audit.data : []);
  renderLedger(ledger.ok ? ledger.data : []);
  renderAudit(audit.ok ? audit.data : []);
  await renderHistory();
}

function renderPayment(p, auditLogs) {
  const visited = new Set(['CREATED']);
  for (const a of auditLogs) {
    if (a.entityType === 'PAYMENT' && a.action === 'STATUS_CHANGED') visited.add(a.newValue);
  }
  const path = BAD.has(p.status) ? ['CREATED', 'PENDING', p.status] : MAIN_PATH;
  const list = $('states');
  list.replaceChildren();
  path.forEach((s, i) => {
    if (i > 0) list.append(el('li', { class: 'arrow', 'aria-hidden': 'true', text: '→' }));
    const cls = s === p.status ? `current ${tone(s)}` : visited.has(s) ? 'done' : '';
    list.append(el('li', { class: cls, text: s.replace('_', ' ') }));
  });

  const messages = {
    CREATED: ['Stored, not yet sent to the processor.', 'wait'],
    PENDING: [p.failureCode === 'PROCESSOR_TIMEOUT'
      ? `The processor timed out (attempt ${p.attemptCount}). The outcome is unknown, so it stays PENDING; `
        + 'the retry job will ask the processor.'
      : 'Waiting for the processor.', 'wait'],
    SUCCESS: ['Charged. The ledger has a balanced debit and credit.', 'ok'],
    FAILED: [`Failed: ${p.failureCode || ''}${p.failureMessage ? ` (${p.failureMessage})` : ''}`, 'bad'],
    CANCELLED: ['Cancelled before processing.', 'bad'],
    REFUND_PENDING: ['Refund in progress.', 'wait'],
    REFUNDED: ['Refunded. The ledger has a reversing transaction.', 'ok'],
  };
  const [msg, t] = messages[p.status] || ['', ''];
  note('status-note', msg, t);

  const details = $('details');
  details.replaceChildren();
  const rows = [
    ['Payment', el('span', { class: 'mono', text: p.id })],
    ['Amount', money(p.amount, p.currency)],
    ['Attempts', String(p.attemptCount)],
    ['Processor id', p.providerPaymentId ? el('span', { class: 'mono', text: p.providerPaymentId }) : '—'],
    ['Updated', time(p.updatedAt)],
  ];
  for (const [k, v] of rows) details.append(el('dt', { text: k }), el('dd', {}, v));

  $('refund-button').disabled = p.status !== 'SUCCESS';
  // Replaying only makes sense for the payment that request created.
  $('replay-button').disabled = state.lastRequest?.paymentId !== p.id;
}

function renderLedger(entries) {
  const table = $('ledger');
  table.replaceChildren();
  $('ledger-empty').hidden = entries.length > 0;
  if (!entries.length) return;
  table.append(el('thead', {}, el('tr', {},
    el('th', { text: 'Transaction' }), el('th', { text: 'Account' }), el('th', { text: 'Debit' }),
    el('th', { text: 'Credit' }))));
  const body = el('tbody');
  for (const e of entries) {
    const amount = money(e.amount, e.currency);
    body.append(el('tr', {},
      el('td', { class: 'mono', text: e.transactionId }),
      el('td', { class: 'mono', text: e.accountId.replace(/:.*/, '') }),
      el('td', { class: 'num', text: e.entryType === 'DEBIT' ? amount : '' }),
      el('td', { class: 'num', text: e.entryType === 'CREDIT' ? amount : '' })));
  }
  table.append(body);
}

function renderAudit(logs) {
  const list = $('audit');
  list.replaceChildren();
  $('audit-empty').hidden = logs.length > 0;
  for (const a of logs) {
    const change = a.oldValue || a.newValue ? ` ${a.oldValue ?? ''} → ${a.newValue ?? ''}` : '';
    list.append(el('li', {},
      el('div', {}, el('strong', { text: `${a.entityType} ${a.action.replaceAll('_', ' ').toLowerCase()}` }), change),
      el('div', { class: 'when' }, time(a.createdAt), ' · ', el('span', { class: 'who', text: a.actor }))));
  }
  list.scrollTop = list.scrollHeight; // newest entry in view
}

async function renderHistory() {
  const res = await api('GET', '/api/v1/payments?limit=20');
  const payments = res.ok ? res.data : [];
  const table = $('history');
  table.replaceChildren();
  $('history-empty').hidden = payments.length > 0;
  if (!payments.length) return;
  table.append(el('thead', {}, el('tr', {},
    el('th', { text: 'Payment' }), el('th', { text: 'Amount' }), el('th', { text: 'Status' }),
    el('th', { text: 'Failure' }), el('th', { text: 'Created' }))));
  const body = el('tbody');
  for (const p of payments) {
    const selected = p.id === state.current?.id ? ' selected' : '';
    body.append(el('tr', { class: `clickable${selected}`, onclick: () => show(p.id) },
      el('td', { class: 'mono', text: p.id }),
      el('td', { class: 'num', text: money(p.amount, p.currency) }),
      el('td', {}, el('span', { class: `pill ${tone(p.status)}`, text: p.status })),
      el('td', { text: p.failureCode || '' }),
      el('td', { text: time(p.createdAt) })));
  }
  table.append(body);
}

async function renderDownstream() {
  if (!state.session) return;
  const [stats, notes] = await Promise.all([
    api('GET', '/api/v1/analytics/daily'),
    api('GET', `/api/v1/customers/${state.session.customerId}/notifications`),
  ]);
  const today = stats.ok ? stats.data[stats.data.length - 1] : null;
  const dl = $('stats');
  dl.replaceChildren();
  const rows = today ? [
    ['Succeeded', `${today.paymentsSucceeded} (${money(today.amountSucceeded, today.currency)})`],
    ['Failed', String(today.paymentsFailed)],
    ['Refunded', `${today.refundsSucceeded} (${money(today.amountRefunded, today.currency)})`],
  ] : [['Totals', 'none yet']];
  for (const [k, v] of rows) dl.append(el('dt', { text: k }), el('dd', { text: v }));

  const list = $('notifications');
  list.replaceChildren();
  const items = notes.ok ? notes.data : [];
  $('notifications-empty').hidden = items.length > 0;
  for (const n of items.slice().reverse()) {
    list.append(el('li', {}, el('strong', { text: n.template.replaceAll('_', ' ').toLowerCase() }),
      ` to ${n.recipient} · ${time(n.createdAt)}`));
  }
}

function note(id, text, cls = '') {
  const node = $(id);
  node.textContent = text;
  node.className = `note ${cls}`;
}

function setBusy(busy) {
  $('pay-button').disabled = busy;
  $('pay-button').textContent = busy ? 'Processing…' : 'Pay';
}

// Poll while the payment is still moving (PENDING retries, refunds in flight).
function schedulePoll() {
  clearTimeout(state.pollTimer);
  if (!state.current || !WAIT.has(state.current.status)) return;
  state.pollTimer = setTimeout(async () => {
    await refresh();
    schedulePoll();
  }, 1500);
}

// ---------- start ----------

function renderCards() {
  const container = $('cards');
  CARDS.forEach((c, i) => {
    const input = el('input', { type: 'radio', name: 'card', value: c.token, id: `card-${c.token}` });
    if (i === 0) input.checked = true;
    container.append(el('label', { class: 'card-option', for: `card-${c.token}` }, input,
      el('span', {}, el('div', { class: 'name' }, c.name, ' ', el('code', { text: c.token })),
        el('div', { class: 'desc', text: c.desc }))));
  });
}

async function start() {
  renderCards();
  $('pay-form').addEventListener('submit', pay);
  $('replay-button').addEventListener('click', replay);
  $('refund-button').addEventListener('click', refund);
  setBusy(true);
  try {
    await ensureSession();
    setBusy(false);
    await renderHistory();
    await renderDownstream();
    setInterval(renderDownstream, 3000);
  } catch (e) {
    const box = $('session-error');
    box.textContent = `${e.message}. Reload the page to try again.`;
    box.hidden = false;
  }
}

start();
