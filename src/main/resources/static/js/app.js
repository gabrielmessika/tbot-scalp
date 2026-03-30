// T-Bot Scalp — app.js
// Main entry point: init, polling, routing

import { fetchJson, el } from './utils.js';
import { initLogs, refreshLogs } from './analysis.js';
import { renderBacktest, exportBacktestCSV } from './backtest.js';
import { refreshTrades, refreshHistory, renderHistoryTable } from './trading.js';
import { refreshStats } from './stats.js';
import { startCountdown } from './countdown.js';

const POLL_INTERVAL = 15000; // 15s
const TRADE_POLL_INTERVAL = 10000; // 10s

let activeTab = 'tab-backtest';

// ==================== INIT ====================

export function initApp() {
    initLogs();
    initTabListeners();
    pollState();
    pollResults();

    setInterval(pollState, POLL_INTERVAL);
    setInterval(() => {
        if (activeTab === 'tab-trades') refreshTrades();
        else if (activeTab === 'tab-history') refreshHistory();
        else if (activeTab === 'tab-logs') refreshLogs();
    }, TRADE_POLL_INTERVAL);

    // Start countdown for analysis cycle
    startCountdown(POLL_INTERVAL / 1000);
}

function initTabListeners() {
    document.querySelectorAll('[data-bs-toggle="tab"]').forEach(tab => {
        tab.addEventListener('shown.bs.tab', (e) => {
            activeTab = e.target.getAttribute('data-bs-target')?.replace('#', '') || '';
            onTabShown(activeTab);
        });
    });
}

function onTabShown(tab) {
    if (tab === 'tab-logs') refreshLogs();
    else if (tab === 'tab-trades') refreshTrades();
    else if (tab === 'tab-history') refreshHistory();
    else if (tab === 'tab-status') refreshStatus();
    else if (tab === 'tab-stats') refreshStats();
}

// ==================== POLLING ====================

async function pollState() {
    try {
        const data = await fetchJson('/api/state');
        if (!data) return;
        updateStartupBanner(data);
        updateWsStatus(data.wsConnected);
        updateLastUpdate();
    } catch (e) { /* ignore */ }
}

async function pollResults() {
    try {
        const data = await fetchJson('/api/results');
        if (!data) return;
        if (data.backtest) renderBacktest(data.backtest);
    } catch (e) { /* ignore */ }
}

// ==================== STARTUP BANNER ====================

function updateStartupBanner(data) {
    const banner = el('startup-banner');
    if (!banner) return;
    if (!data.startupComplete) {
        banner.style.display = 'block';
        el('startup-phase').textContent = data.startupPhase || 'Initializing...';
    } else {
        banner.style.display = 'none';
    }
}

function updateWsStatus(connected) {
    const wsEl = el('ws-status');
    if (!wsEl) return;
    wsEl.textContent = connected ? 'WS: ON' : 'WS: OFF';
    wsEl.className = `badge ${connected ? 'bg-success' : 'bg-danger'}`;
}

function updateLastUpdate() {
    const lu = el('last-update');
    if (lu) lu.textContent = 'Updated: ' + new Date().toLocaleTimeString();
}

// ==================== ACTIONS ====================

window.runAnalysis = async function () {
    const btn = el('btn-analyze');
    const overlay = el('loading-overlay');
    btn.disabled = true;
    if (overlay) { overlay.classList.add('show'); el('loading-text').textContent = 'Analysis in progress...'; }

    try {
        const data = await fetchJson('/api/analyze');
        if (data?.backtest) renderBacktest(data.backtest);
        startCountdown(POLL_INTERVAL / 1000);
    } catch (e) {
        console.error('Analysis failed:', e);
    } finally {
        btn.disabled = false;
        if (overlay) overlay.classList.remove('show');
    }
};

window.runBacktest = async function () {
    const btn = el('btn-backtest');
    const overlay = el('loading-overlay');
    btn.disabled = true;
    if (overlay) { overlay.classList.add('show'); el('loading-text').textContent = 'Backtest running... This may take a few minutes.'; }

    try {
        const res = await fetch('/api/backtest', { method: 'POST' });
        if (res.status === 409) { alert('Backtest already running'); return; }
        const data = await res.json();
        if (data?.backtest) renderBacktest(data.backtest);
    } catch (e) {
        console.error('Backtest failed:', e);
    } finally {
        btn.disabled = false;
        if (overlay) overlay.classList.remove('show');
    }
};

// ==================== STATUS TAB ====================

async function refreshStatus() {
    try {
        const [state, config, balance] = await Promise.all([
            fetchJson('/api/bot/state'),
            fetchJson('/api/config'),
            fetchJson('/api/bot/exchange/balance')
        ]);

        // Overview cards
        const overview = el('status-overview');
        if (state) {
            const risk = state.risk || {};
            overview.innerHTML = `
                <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Mode</div><div class="stat-value">${state.liveTrading ? '<span class="badge badge-live">LIVE</span>' : state.autoTrade ? '<span class="badge badge-simulated">DRY-RUN</span>' : '<span class="badge badge-off">OFF</span>'}</div></div></div>
                <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Exchange</div><div class="stat-value">${state.exchange || '—'}</div></div></div>
                <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">WebSocket</div><div class="stat-value">${state.wsConnected ? '<span class="badge bg-success">Connected</span>' : '<span class="badge bg-danger">Disconnected</span>'}</div></div></div>
                <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Balance</div><div class="stat-value">${balance?.balance != null ? '$' + Number(balance.balance).toFixed(2) : '—'}</div></div></div>
                <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Open Positions</div><div class="stat-value">${state.openCount || 0}</div></div></div>
                <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Drawdown</div><div class="stat-value ${(risk.currentDrawdown || 0) > 5 ? 'pnl-negative' : ''}">${(risk.currentDrawdown || 0).toFixed(2)}%</div></div></div>
                <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Daily P&L</div><div class="stat-value ${(risk.dailyPnl || 0) >= 0 ? 'pnl-positive' : 'pnl-negative'}">${(risk.dailyPnl || 0) >= 0 ? '+' : ''}$${(risk.dailyPnl || 0).toFixed(2)}</div></div></div>
                <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Startup</div><div class="stat-value">${state.startupComplete ? '<span class="badge bg-success">Complete</span>' : '<span class="badge bg-warning">' + (state.startupPhase || 'Init') + '</span>'}</div></div></div>
            `;
        }

        // Config display
        const cfgEl = el('config-container');
        if (config) {
            const sections = [
                { title: 'Market Data', data: { Exchange: config.exchange, Coins: Array.isArray(config.coins) ? config.coins.length + ' coins' : config.coins, Timeframes: config.timeframes } },
                { title: 'Position Sizing', data: { 'Position Size': config.positionSizePercent + '%', 'Max Leverage': config.maxLeverage + 'x', 'Max SL': config.maxSlPercent + '%' } },
                { title: 'Risk Management', data: { 'Threshold': config.confidentThreshold, 'Max Positions': config.maxOpenPositions, 'Max Daily Loss': config.maxDailyLossPercent + '%', 'Max Drawdown': config.maxDrawdownPercent + '%', 'Max Loss/Trade': config.maxLossPerTradePercent + '%', 'Max Margin': config.maxMarginUsagePercent + '%' } },
                { title: 'Backtest', data: { Sessions: config.backtestSessions, Portfolios: config.portfolioBalances?.join(', ') } },
                { title: 'Strategies', data: config.strategies || {} }
            ];

            cfgEl.innerHTML = '<div class="row g-3">' + sections.map(s => `
                <div class="col-lg-4 col-md-6">
                    <div class="card">
                        <div class="card-header fw-bold small">${s.title}</div>
                        <div class="card-body p-2">
                            <table class="table table-sm mb-0">${Object.entries(s.data).map(([k, v]) =>
                `<tr><td class="text-secondary">${k}</td><td class="fw-bold">${v}</td></tr>`
            ).join('')}</table>
                        </div>
                    </div>
                </div>`).join('') + '</div>';
        }
    } catch (e) { console.error('Status error:', e); }
}

window.refreshStatus = refreshStatus;
window.refreshLogs = refreshLogs;
window.renderHistoryTable = renderHistoryTable;
