// Camel Integration Console: a dependency-free single-page UI over /api.
'use strict';

const app = document.getElementById('app');
const LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF'];
let settings = { grafanaUrl: '', dashboardUid: 'camel-service', writeProtected: false };
let refreshTimer = null;

// ---------- helpers ----------
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const badge = (status) => `<span class="badge s-${esc(status)}">${esc(status)}</span>`;
const ago = (iso) => {
  if (!iso) return 'never';
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  return `${Math.round(s / 3600)}h ago`;
};
const fmtTime = (iso) => (iso ? new Date(iso).toLocaleString() : '');

function toast(message) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => el.classList.remove('show'), 4000);
}

async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  const token = sessionStorageGet('consoleToken');
  if (token) headers['X-Console-Token'] = token;
  const res = await fetch(`/api${path}`, { ...options, headers });
  const body = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) throw new Error((body && body.error) || `HTTP ${res.status}`);
  return body;
}

function sessionStorageGet(key) { try { return sessionStorage.getItem(key); } catch { return null; } }
function sessionStorageSet(key, v) { try { sessionStorage.setItem(key, v); } catch { /* private mode */ } }

function grafanaDashboardUrl(service, extra = '') {
  return `${settings.grafanaUrl}/d/${encodeURIComponent(settings.dashboardUid)}?var-service=${encodeURIComponent(service)}${extra}`;
}

function setActiveNav(name) {
  document.querySelectorAll('[data-nav]').forEach((a) => a.classList.toggle('active', a.dataset.nav === name));
}

function autoRefresh(fn, ms) {
  clearInterval(refreshTimer);
  refreshTimer = fn ? setInterval(fn, ms) : null;
}

// ---------- router ----------
async function route() {
  autoRefresh(null);
  const parts = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  try {
    if (parts[0] === 'service' && parts[1]) {
      setActiveNav('services');
      await renderService(parts[1], parts[2] || 'overview');
    } else if (parts[0] === 'audit') {
      setActiveNav('audit');
      await renderAudit();
    } else {
      setActiveNav('services');
      await renderServices();
    }
  } catch (e) {
    app.innerHTML = `<div class="panel"><strong>Could not load this page.</strong><p class="muted">${esc(e.message)}</p></div>`;
  }
}

// ---------- services list ----------
async function renderServices() {
  const draw = async () => {
    const services = await api('/services');
    const counts = services.reduce((acc, s) => ({ ...acc, [s.status]: (acc[s.status] || 0) + 1 }), {});
    const filter = (document.getElementById('svc-filter') || {}).value || '';
    const rows = services
      .filter((s) => !filter || `${s.name} ${s.domain} ${s.description}`.toLowerCase().includes(filter.toLowerCase()))
      .map((s) => `
        <tr>
          <td><a href="#/service/${encodeURIComponent(s.name)}">${esc(s.name)}</a><div class="muted">${esc(s.description)}</div></td>
          <td>${esc(s.domain)}</td>
          <td>${badge(s.status)}</td>
          <td>${s.instances.length}</td>
          <td>v${s.configVersion}${s.configPending ? ' <span class="muted">(applying)</span>' : ''}</td>
          <td class="mono">${s.port ?? ''}</td>
          <td><a href="#/service/${encodeURIComponent(s.name)}/logs">Logs</a> · <a href="${esc(grafanaDashboardUrl(s.name))}" target="_blank" rel="noopener">Dashboard ↗</a></td>
        </tr>`).join('');
    const body = document.getElementById('svc-body');
    if (body) {
      body.innerHTML = rows || '<tr><td colspan="7" class="muted">No services match.</td></tr>';
      document.getElementById('svc-cards').innerHTML = summaryCards(services.length, counts);
      return;
    }
    app.innerHTML = `
      <h1>Services</h1>
      <p class="muted">Every integration service, its health from heartbeats, and its configuration version.</p>
      <div class="cards" id="svc-cards">${summaryCards(services.length, counts)}</div>
      <div class="toolbar"><input id="svc-filter" class="grow" placeholder="Filter by name, domain or description" value="${esc(filter)}"></div>
      <div class="panel table-wrap"><table>
        <thead><tr><th>Service</th><th>Domain</th><th>Status</th><th>Instances</th><th>Config</th><th>Port</th><th></th></tr></thead>
        <tbody id="svc-body">${rows || '<tr><td colspan="7" class="muted">No services registered. Run scripts/new-service.sh to add one.</td></tr>'}</tbody>
      </table></div>`;
    document.getElementById('svc-filter').addEventListener('input', draw);
  };
  await draw();
  autoRefresh(() => draw().catch(() => {}), 10000);
}

function summaryCards(total, counts) {
  return [['Services', total, ''], ['Up', counts.UP || 0, 's-UP'], ['Stale', counts.STALE || 0, 's-STALE'],
    ['Down', counts.DOWN || 0, 's-DOWN'], ['Never seen', counts.UNKNOWN || 0, 's-UNKNOWN']]
    .map(([label, value, cls]) => `<div class="panel card"><div class="label">${label}</div><div class="value ${cls}">${value}</div></div>`).join('');
}

// ---------- service detail ----------
async function renderService(name, tab) {
  const svc = await api(`/services/${encodeURIComponent(name)}`);
  const tabs = [['overview', 'Overview'], ['config', 'Configuration'], ['logs', 'Logs'], ['dashboard', 'Dashboard'], ['audit', 'History']];
  app.innerHTML = `
    <p><a href="#/">← All services</a></p>
    <h1>${esc(svc.name)} ${badge(svc.status)}</h1>
    <p class="muted">${esc(svc.description)} · domain <strong>${esc(svc.domain)}</strong> · config v${svc.configVersion}</p>
    <div class="tabs">${tabs.map(([id, label]) => `<a href="#/service/${encodeURIComponent(name)}/${id}" class="${tab === id ? 'active' : ''}">${label}</a>`).join('')}</div>
    <div id="tab"></div>`;
  const el = document.getElementById('tab');
  if (tab === 'config') return renderConfig(svc, el);
  if (tab === 'logs') return renderLogs(svc, el);
  if (tab === 'dashboard') return renderDashboard(svc, el);
  if (tab === 'audit') return renderHistory(svc, el);
  return renderOverview(svc, el);
}

function renderOverview(svc, el) {
  if (!svc.instances.length) {
    el.innerHTML = `<div class="panel"><p>No heartbeat received yet. Start the service; it reports here every poll interval.</p>
      <p class="muted">Services find the console through <code>platform.console.url</code> (env <code>PLATFORM_CONSOLE_URL</code>).</p></div>`;
    return;
  }
  el.innerHTML = svc.instances.map((i) => {
    const h = i.heartbeat;
    const routes = (h.routes || []).map((r) => `<tr><td class="mono">${esc(r.id)}</td><td>${esc(r.status)}</td><td class="mono">${esc(r.from)}</td></tr>`).join('');
    return `<div class="panel" style="margin-bottom:12px">
      <h2 style="margin-top:0">${esc(h.instance)} ${i.stale ? badge('STALE') : badge(h.status === 'UP' ? 'UP' : 'DOWN')}</h2>
      <div class="cards">
        <div class="card"><div class="label">Last heartbeat</div><div>${ago(i.receivedAt)}</div></div>
        <div class="card"><div class="label">Started</div><div>${fmtTime(new Date(h.startedAtEpochMs).toISOString())}</div></div>
        <div class="card"><div class="label">Version</div><div class="mono">${esc(h.version)}</div></div>
        <div class="card"><div class="label">Camel</div><div class="mono">${esc(h.camelVersion)}</div></div>
        <div class="card"><div class="label">Environment</div><div>${esc(h.environment)}</div></div>
        <div class="card"><div class="label">Config applied</div><div>v${h.appliedConfigVersion < 0 ? '–' : h.appliedConfigVersion}${h.appliedConfigVersion < svc.configVersion ? ' <span class="muted">(v' + svc.configVersion + ' pending)</span>' : ''}</div></div>
      </div>
      <h2>Routes</h2>
      <div class="table-wrap"><table><thead><tr><th>Route</th><th>Status</th><th>From</th></tr></thead><tbody>${routes || '<tr><td colspan="3" class="muted">No routes</td></tr>'}</tbody></table></div>
    </div>`;
  }).join('');
  autoRefresh(() => route(), 15000);
}

// ---------- configuration editor ----------
async function renderConfig(svc, el) {
  const config = await api(`/services/${encodeURIComponent(svc.name)}/config`);
  const levelRow = (logger = '', level = 'INFO') => `
    <div class="kv-row" data-kind="level">
      <input placeholder="Logger, e.g. ROOT, org.apache.camel, com.integration" value="${esc(logger)}">
      <select>${LEVELS.map((l) => `<option ${l === level ? 'selected' : ''}>${l}</option>`).join('')}</select>
      <button type="button" data-remove>Remove</button>
    </div>`;
  const propRow = (key = '', value = '') => `
    <div class="kv-row props" data-kind="prop">
      <input placeholder="Property, e.g. platform.error-handling.maximum-redeliveries" value="${esc(key)}">
      <input placeholder="Value" value="${esc(value)}">
      <button type="button" data-remove>Remove</button>
    </div>`;

  el.innerHTML = `
    <div class="panel">
      <h2 style="margin-top:0">Log levels <span class="muted">· applied live within one poll, no restart</span></h2>
      <div id="levels">${Object.entries(config.logLevels).map(([k, v]) => levelRow(k, v)).join('')}</div>
      <button type="button" id="add-level">Add logger</button>

      <h2>Properties <span class="muted">· applied on the next restart</span></h2>
      <div class="note">These override the service's <code>application.yml</code> and the global <code>config/global/monitoring.yml</code>,
        but not environment variables. Use them for operational settings (retries, timeouts, feature flags). Connection settings (URLs, hosts, queue managers, channels) and credentials are rejected: they live in Git and the secret store.</div>
      <div id="props">${Object.entries(config.properties).map(([k, v]) => propRow(k, v)).join('')}</div>
      <button type="button" id="add-prop">Add property</button>

      <h2>Change record</h2>
      <div class="toolbar">
        <input id="changed-by" placeholder="Your name" value="${esc(sessionStorageGet('changedBy') || '')}">
        <input id="comment" class="grow" placeholder="Reason for the change (kept in the audit trail)">
        ${settings.writeProtected ? '<input id="token" type="password" placeholder="Console token">' : ''}
        <button class="primary" id="save">Save as v${config.version + 1}</button>
      </div>
      <p class="muted">Current: v${config.version}${config.updatedBy ? ` by ${esc(config.updatedBy)}, ${fmtTime(config.updatedAt)}` : ' (defaults only)'}.</p>
    </div>`;

  el.addEventListener('click', (e) => { if (e.target.matches('[data-remove]')) e.target.closest('.kv-row').remove(); });
  document.getElementById('add-level').onclick = () => document.getElementById('levels').insertAdjacentHTML('beforeend', levelRow());
  document.getElementById('add-prop').onclick = () => document.getElementById('props').insertAdjacentHTML('beforeend', propRow());
  document.getElementById('save').onclick = async () => {
    const collect = (sel) => Object.fromEntries([...el.querySelectorAll(sel)]
      .map((row) => [...row.querySelectorAll('input,select')].map((i) => i.value.trim()))
      .filter(([k]) => k));
    const changedBy = document.getElementById('changed-by').value.trim();
    if (!changedBy) { toast('Enter your name so the change is attributed.'); return; }
    sessionStorageSet('changedBy', changedBy);
    const tokenInput = document.getElementById('token');
    if (tokenInput && tokenInput.value) sessionStorageSet('consoleToken', tokenInput.value);
    try {
      const saved = await api(`/services/${encodeURIComponent(svc.name)}/config`, {
        method: 'PUT',
        body: JSON.stringify({
          logLevels: collect('[data-kind=level]'),
          properties: collect('[data-kind=prop]'),
          changedBy,
          comment: document.getElementById('comment').value.trim(),
        }),
      });
      toast(`Saved v${saved.version}. Log levels apply within one poll; properties on restart.`);
      route();
    } catch (err) {
      toast(`Not saved: ${err.message}`);
    }
  };
}

// ---------- logs ----------
async function renderLogs(svc, el) {
  const state = JSON.parse(sessionStorageGet(`logs:${svc.name}`) || '{}');
  el.innerHTML = `
    <div class="toolbar">
      ${['ERROR', 'WARN', 'INFO', 'DEBUG'].map((l) => `<label><input type="checkbox" name="lvl" value="${l}" ${(state.levels || ['ERROR', 'WARN', 'INFO']).includes(l) ? 'checked' : ''}> ${l}</label>`).join('')}
      <input id="search" class="grow" placeholder="Text contains…" value="${esc(state.search || '')}">
      <input id="corr" placeholder="Correlation ID" value="${esc(state.corr || '')}">
      <select id="minutes">${[[15, '15 min'], [60, '1 hour'], [360, '6 hours'], [1440, '24 hours']].map(([v, l]) => `<option value="${v}" ${Number(state.minutes || 60) === v ? 'selected' : ''}>${l}</option>`).join('')}</select>
      <label><input type="checkbox" id="live" ${state.live ? 'checked' : ''}> Live</label>
      <button class="primary" id="run">Search</button>
    </div>
    <p class="muted" id="logql"></p>
    <div class="panel table-wrap"><table>
      <thead><tr><th style="width:170px">Time</th><th>Level</th><th>Message</th><th>Correlation</th></tr></thead>
      <tbody id="log-body"><tr><td colspan="4" class="muted">Loading…</td></tr></tbody>
    </table></div>`;

  const run = async () => {
    const levels = [...el.querySelectorAll('[name=lvl]:checked')].map((c) => c.value);
    const s = { levels, search: el.querySelector('#search').value, corr: el.querySelector('#corr').value,
      minutes: el.querySelector('#minutes').value, live: el.querySelector('#live').checked };
    sessionStorageSet(`logs:${svc.name}`, JSON.stringify(s));
    const q = new URLSearchParams({ minutes: s.minutes, limit: 300 });
    levels.forEach((l) => q.append('level', l));
    if (s.search) q.set('search', s.search);
    if (s.corr) q.set('correlationId', s.corr);
    const body = el.querySelector('#log-body');
    try {
      const res = await api(`/services/${encodeURIComponent(svc.name)}/logs?${q}`);
      const explore = `${settings.grafanaUrl}/explore?left=${encodeURIComponent(JSON.stringify({ datasource: 'loki', queries: [{ refId: 'A', expr: res.query }], range: { from: `now-${s.minutes}m`, to: 'now' } }))}`;
      el.querySelector('#logql').innerHTML = `LogQL: <code>${esc(res.query)}</code> · <a href="${esc(explore)}" target="_blank" rel="noopener">Open in Grafana Explore ↗</a>`;
      body.innerHTML = res.lines.map((l) => `
        <tr class="logline">
          <td class="mono">${esc(fmtTime(l.timestamp))}</td>
          <td class="l-${esc(l.level)}">${esc(l.level)}</td>
          <td class="msg">${esc(l.message)}${l.logger ? `<div class="muted mono">${esc(l.logger)}${l.routeId ? ' · route ' + esc(l.routeId) : ''}</div>` : ''}</td>
          <td class="mono">${l.correlationId ? `<a href="#" data-corr="${esc(l.correlationId)}">${esc(l.correlationId.slice(0, 13))}…</a>` : ''}</td>
        </tr>`).join('') || '<tr><td colspan="4" class="muted">No log lines for these filters.</td></tr>';
    } catch (err) {
      body.innerHTML = `<tr><td colspan="4">${esc(err.message)}</td></tr>`;
    }
    autoRefresh(s.live ? () => run() : null, 5000);
  };
  el.addEventListener('click', (e) => {
    if (e.target.dataset.corr) { e.preventDefault(); el.querySelector('#corr').value = e.target.dataset.corr; run(); }
  });
  el.querySelector('#run').onclick = run;
  el.querySelector('#live').onchange = run;
  el.querySelector('#search').addEventListener('keydown', (e) => { if (e.key === 'Enter') run(); });
  el.querySelector('#corr').addEventListener('keydown', (e) => { if (e.key === 'Enter') run(); });
  await run();
}

// ---------- dashboard ----------
function renderDashboard(svc, el) {
  const url = grafanaDashboardUrl(svc.name, '&kiosk&theme=' + (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
  el.innerHTML = `
    <p class="muted">Grafana dashboard filtered to this service. <a href="${esc(grafanaDashboardUrl(svc.name))}" target="_blank" rel="noopener">Open in Grafana ↗</a></p>
    <iframe class="dashboard" src="${esc(url)}" title="Grafana dashboard for ${esc(svc.name)}"></iframe>`;
}

// ---------- audit ----------
async function renderHistory(svc, el) {
  el.innerHTML = auditTable(await api(`/audit?service=${encodeURIComponent(svc.name)}`), false);
}

async function renderAudit() {
  const entries = await api('/audit');
  app.innerHTML = `<h1>Audit trail</h1><p class="muted">Every configuration change, newest first.</p>${auditTable(entries, true)}`;
}

function auditTable(entries, withService) {
  const fmtMap = (m) => Object.entries(m || {}).map(([k, v]) => `<div class="mono">${esc(k)} = ${esc(v)}</div>`).join('') || '<span class="muted">none</span>';
  return `<div class="panel table-wrap"><table>
    <thead><tr><th>When</th>${withService ? '<th>Service</th>' : ''}<th>Who</th><th>Version</th><th>Why</th><th>Log levels</th><th>Properties</th></tr></thead>
    <tbody>${entries.map((e) => `<tr>
      <td>${esc(fmtTime(e.at))}</td>
      ${withService ? `<td><a href="#/service/${encodeURIComponent(e.service)}/config">${esc(e.service)}</a></td>` : ''}
      <td>${esc(e.changedBy)}</td><td>v${e.fromVersion} → v${e.toVersion}</td><td>${esc(e.comment)}</td>
      <td>${fmtMap(e.logLevels)}</td><td>${fmtMap(e.properties)}</td></tr>`).join('')
      || `<tr><td colspan="${withService ? 7 : 6}" class="muted">No changes yet.</td></tr>`}</tbody>
  </table></div>`;
}

// ---------- boot ----------
(async () => {
  try { settings = await api('/settings'); } catch { /* defaults */ }
  document.getElementById('grafana-link').href = settings.grafanaUrl || '#';
  window.addEventListener('hashchange', route);
  route();
})();
