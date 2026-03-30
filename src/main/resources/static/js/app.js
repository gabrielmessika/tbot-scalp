// ===== T-Bot Scalp — app.js =====
// Main entry point, polling, tab init

const state = {
    selectedSession: 0,
    selectedPortfolio: 0,
    backtestData: null
};

// ===== Polling =====
async function pollState() {
    try {
        const res = await fetch('/api/state');
        if (!res.ok) return;
        const data = await res.json();
        updateStartupBanner(data);
        updateWsStatus(data.wsConnected);
        updateModeBadge(data);
    } catch (e) { /* ignore */ }
}

async function pollResults() {
    try {
        const res = await fetch('/api/results');
        if (res.status === 204) return;
        const data = await res.json();
        if (data.logs) renderLogs(data.logs);
        if (data.backtest) {
            state.backtestData = data.backtest;
            renderBacktest(data.backtest);
        }
    } catch (e) { /* ignore */ }
}

function updateStartupBanner(data) {
    const banner = document.getElementById('startup-banner');
    const phase = document.getElementById('startup-phase');
    if (!data.startupComplete) {
        banner.classList.remove('d-none');
        phase.textContent = data.startupPhase || 'Initializing...';
    } else {
        banner.classList.add('d-none');
    }
}

function updateWsStatus(connected) {
    const el = document.getElementById('ws-status');
    el.textContent = connected ? 'WS: Connected' : 'WS: Offline';
    el.className = `badge ${connected ? 'bg-success' : 'bg-secondary'}`;
}

function updateModeBadge(data) {
    const el = document.getElementById('mode-badge');
    if (data.liveTrading) { el.textContent = 'LIVE'; el.className = 'badge bg-danger'; }
    else if (data.autoTrade) { el.textContent = 'DRY-RUN'; el.className = 'badge bg-warning text-dark'; }
    else { el.textContent = 'MANUAL'; el.className = 'badge bg-info'; }
}

// ===== Actions =====
window.runAnalysis = async function () {
    try {
        const res = await fetch('/api/analyze', { method: 'POST' });
        const data = await res.json();
        if (data.logs) renderLogs(data.logs);
        if (data.alerts && data.alerts.length > 0) {
            const badge = document.getElementById('trades-badge');
            badge.textContent = data.alerts.length;
            badge.classList.remove('d-none');
        }
    } catch (e) { console.error('Analysis failed:', e); }
};

window.runBacktest = async function () {
    const btn = document.getElementById('btn-backtest');
    const loading = document.getElementById('backtest-loading');
    const empty = document.getElementById('backtest-empty');
    const content = document.getElementById('backtest-content');

    btn.disabled = true;
    loading.classList.remove('d-none');
    empty.classList.add('d-none');
    content.classList.add('d-none');

    try {
        const res = await fetch('/api/backtest', { method: 'POST' });
        if (res.status === 409) { alert('Backtest already running'); return; }
        const data = await res.json();
        if (data.logs) renderLogs(data.logs);
        if (data.backtest) {
            state.backtestData = data.backtest;
            renderBacktest(data.backtest);
        }
    } catch (e) { console.error('Backtest failed:', e); }
    finally {
        btn.disabled = false;
        loading.classList.add('d-none');
    }
};

window.refreshLogs = function () { pollResults(); };

// ===== Logs =====
function renderLogs(logs) {
    const el = document.getElementById('log-output');
    const filter = document.getElementById('log-filter').value.toLowerCase();
    const lines = logs
        .filter(l => !filter || l.message.toLowerCase().includes(filter))
        .map(l => {
            const time = new Date(l.timestamp).toLocaleTimeString();
            const cls = `log-${l.level}`;
            return `<span class="${cls}">[${time}] [${l.level}] ${escapeHtml(l.message)}</span>`;
        });
    el.innerHTML = lines.join('\n');
    el.scrollTop = el.scrollHeight;
}

// ===== Backtest =====
function renderBacktest(bt) {
    if (!bt || !bt.sessions || bt.sessions.length === 0) return;

    const empty = document.getElementById('backtest-empty');
    const content = document.getElementById('backtest-content');
    empty.classList.add('d-none');
    content.classList.remove('d-none');

    renderSessionSelector(bt.sessions);
    renderPortfolioContent();
}

function renderSessionSelector(sessions) {
    const el = document.getElementById('session-selector');
    el.innerHTML = '<div class="btn-group flex-wrap">' +
        sessions.map((s, i) => `<button class="btn btn-sm session-btn ${i === state.selectedSession ? 'btn-warning active' : 'btn-outline-secondary'}"
            onclick="selectSession(${i})" title="${s.sessionDescription || ''}">${s.sessionName} (${s.totalSignals})</button>`).join('') +
        '</div>';
}

window.selectSession = function (idx) {
    state.selectedSession = idx;
    state.selectedPortfolio = 0;
    renderPortfolioContent();
    document.querySelectorAll('.session-btn').forEach((b, i) => {
        b.className = `btn btn-sm session-btn ${i === idx ? 'btn-warning active' : 'btn-outline-secondary'}`;
    });
};

function renderPortfolioContent() {
    const bt = state.backtestData;
    if (!bt) return;
    const session = bt.sessions[state.selectedSession];
    if (!session) return;

    // Portfolio selector
    const pSel = document.getElementById('portfolio-selector');
    pSel.innerHTML = '<div class="btn-group">' +
        session.portfolios.map((p, i) => `<button class="btn btn-sm ${i === state.selectedPortfolio ? 'btn-primary' : 'btn-outline-secondary'}"
            onclick="selectPortfolio(${i})">$${p.initialBalance}</button>`).join('') +
        '</div>';

    // Comparison table
    renderComparisonTable(session);

    // Selected portfolio stats
    const pf = session.portfolios[state.selectedPortfolio];
    if (pf) {
        renderPortfolioStats(pf);
        renderPortfolioTrades(pf);
    }
}

window.selectPortfolio = function (idx) {
    state.selectedPortfolio = idx;
    renderPortfolioContent();
};

function renderComparisonTable(session) {
    const el = document.getElementById('comparison-table');
    const rows = session.portfolios.map(p => `<tr>
        <td>$${p.initialBalance}</td>
        <td>$${p.finalBalance.toFixed(2)}</td>
        <td class="${pnlClass(p.roi)}">${p.roi > 0 ? '+' : ''}${p.roi.toFixed(1)}%</td>
        <td>${p.winRate.toFixed(1)}%</td>
        <td>${p.totalTrades}</td>
        <td class="pnl-positive">${p.wins}</td>
        <td class="pnl-negative">${p.losses}</td>
        <td>${p.skipped}</td>
        <td class="pnl-negative">${p.maxDrawdown.toFixed(1)}%</td>
        <td class="pnl-positive">$${p.bestTrade.toFixed(2)}</td>
        <td class="pnl-negative">$${p.worstTrade.toFixed(2)}</td>
    </tr>`);

    el.innerHTML = `<div class="table-responsive mb-3"><table class="table table-sm table-dark table-hover">
        <thead><tr><th>Initial</th><th>Final</th><th>ROI</th><th>Win Rate</th><th>Trades</th>
        <th>Wins</th><th>Losses</th><th>Skipped</th><th>Max DD</th><th>Best</th><th>Worst</th></tr></thead>
        <tbody>${rows.join('')}</tbody></table></div>`;
}

function renderPortfolioStats(pf) {
    const el = document.getElementById('portfolio-stats');
    const cards = [
        { label: 'Initial', value: `$${pf.initialBalance}`, cls: '' },
        { label: 'Final', value: `$${pf.finalBalance.toFixed(2)}`, cls: pnlClass(pf.roi) },
        { label: 'ROI', value: `${pf.roi > 0 ? '+' : ''}${pf.roi.toFixed(1)}%`, cls: pnlClass(pf.roi) },
        { label: 'Win Rate', value: `${pf.winRate.toFixed(1)}%`, cls: pf.winRate >= 40 ? 'pnl-positive' : 'pnl-negative' },
        { label: 'Total Trades', value: pf.totalTrades, cls: '' },
        { label: 'Max Drawdown', value: `${pf.maxDrawdown.toFixed(1)}%`, cls: 'pnl-negative' },
    ];
    el.innerHTML = cards.map(c => `<div class="col-md-2 col-4"><div class="stat-card">
        <div class="stat-value ${c.cls}">${c.value}</div>
        <div class="stat-label">${c.label}</div></div></div>`).join('');
}

function renderPortfolioTrades(pf) {
    const el = document.getElementById('trades-table');
    if (!pf.trades || pf.trades.length === 0) {
        el.innerHTML = '<p class="text-muted">No trades</p>';
        return;
    }
    const rows = pf.trades.map(t => {
        const pnlCls = t.pnl > 0 ? 'pnl-positive' : t.pnl < 0 ? 'pnl-negative' : 'pnl-neutral';
        const date = t.entryTime ? new Date(t.entryTime).toLocaleString() : '-';
        return `<tr>
            <td>${t.pair}</td><td>${t.timeframe}</td>
            <td class="${t.direction === 'LONG' ? 'pnl-positive' : 'pnl-negative'}">${t.direction}</td>
            <td>${formatPrice(t.entryPrice)}</td>
            <td>${formatPrice(t.exitPrice)}</td>
            <td>${t.leverage}x</td>
            <td>${t.score ? t.score.toFixed(1) : '-'}</td>
            <td>${t.result}</td>
            <td class="${pnlCls}">${t.pnl ? (t.pnl > 0 ? '+' : '') + t.pnl.toFixed(2) : '-'}</td>
            <td class="${pnlCls}">${t.pnlPercent ? (t.pnlPercent > 0 ? '+' : '') + t.pnlPercent.toFixed(1) + '%' : '-'}</td>
            <td>${t.candlesElapsed || '-'}</td>
            <td>${t.balanceAfter ? '$' + t.balanceAfter.toFixed(2) : '-'}</td>
            <td>${date}</td>
        </tr>`;
    });

    el.innerHTML = `<h6 class="mt-3">Trades Detail</h6>
    <div class="table-responsive"><table class="table table-sm table-dark table-hover">
        <thead><tr><th>Pair</th><th>TF</th><th>Dir</th><th>Entry</th><th>Exit</th>
        <th>Lev</th><th>Score</th><th>Result</th><th>P&L $</th><th>P&L %</th>
        <th>Candles</th><th>Balance</th><th>Date</th></tr></thead>
        <tbody>${rows.join('')}</tbody></table></div>`;
}

// ===== Config Tab =====
async function loadConfig() {
    try {
        const res = await fetch('/api/config');
        const data = await res.json();
        const el = document.getElementById('config-content');
        el.innerHTML = `<pre class="log-terminal">${escapeHtml(JSON.stringify(data, null, 2))}</pre>`;
    } catch (e) { /* ignore */ }
}

// ===== Helpers =====
function formatPrice(p) {
    if (!p) return '-';
    if (p >= 1000) return p.toFixed(2);
    if (p >= 1) return p.toFixed(4);
    return p.toFixed(6);
}

function pnlClass(v) {
    return v > 0 ? 'pnl-positive' : v < 0 ? 'pnl-negative' : 'pnl-neutral';
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// ===== Init =====
document.addEventListener('DOMContentLoaded', () => {
    pollState();
    pollResults();
    loadConfig();

    setInterval(pollState, 5000);
    setInterval(pollResults, 30000);

    // Log filter
    document.getElementById('log-filter').addEventListener('input', () => pollResults());
});
