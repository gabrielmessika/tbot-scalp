// Trading tab: open positions, trade journal, history, risk
import { formatPrice, formatUsd, formatPercent, pnlClass, directionBadge, reasonBadge, statusBadge, formatDate, timeAgo, fetchJson, el } from './utils.js';

// ==================== TRADES TAB ====================

export async function refreshTrades() {
    await Promise.all([refreshExecStatus(), refreshRisk(), refreshPositions(), refreshPendingOrders(), refreshJournal()]);
}

async function refreshExecStatus() {
    try {
        const data = await fetchJson('/api/execution-status');
        if (!data) return;
        const badge = el('exec-mode-badge');
        if (data.liveTrading) {
            badge.className = 'badge badge-live';
            badge.textContent = 'LIVE';
        } else if (data.autoTrade) {
            badge.className = 'badge badge-simulated';
            badge.textContent = 'DRY-RUN';
        } else {
            badge.className = 'badge badge-off';
            badge.textContent = 'OFF';
        }
        el('exec-exchange').textContent = data.exchange || '—';
    } catch (e) { console.error('Exec status error:', e); }
}

async function refreshRisk() {
    try {
        const data = await fetchJson('/api/state');
        if (!data?.risk) return;
        const r = data.risk;
        const live = data.liveTrading;
        window._tfSettings = data.timeframeSettings || {};

        // Balance/Equity card — smart display like t-bot
        let balanceCard;
        if (live) {
            balanceCard = `
                <div class="col-md-2 col-6"><div class="stat-card">
                    <div class="stat-label">Equity</div>
                    <div class="stat-value">${formatUsd(r.totalEquity).replace('+', '')}</div>
                    <div class="stat-detail">Available: ${formatUsd(r.availableBalance).replace('+', '')}</div>
                </div></div>`;
        } else {
            const check = Math.abs((r.baseBalance + r.realizedPnl + r.unrealizedPnl) - r.totalEquity) < 0.01;
            balanceCard = `
                <div class="col-md-2 col-6"><div class="stat-card">
                    <div class="stat-label">Equity ${check ? '<span class="text-success">\u2713</span>' : '<span class="text-warning">\u26a0</span>'}</div>
                    <div class="stat-value">${formatUsd(r.totalEquity).replace('+', '')}</div>
                    <div class="stat-detail">Base: $${r.baseBalance?.toFixed(0) || '?'} | Real: <span class="${pnlClass(r.realizedPnl)}">${formatUsd(r.realizedPnl)}</span> | Unreal: <span class="${pnlClass(r.unrealizedPnl)}">${formatUsd(r.unrealizedPnl)}</span></div>
                </div></div>`;
        }

        // Positions card
        const pendingCount = data.pendingOrders || 0;
        const pendingLabel = pendingCount > 0
            ? `<div class="stat-detail" style="color:var(--accent-orange)"><i class="bi bi-hourglass-split"></i> ${pendingCount} pending limit</div>`
            : '';
        const posCard = `
            <div class="col-md-2 col-6"><div class="stat-card">
                <div class="stat-label">Positions</div>
                <div class="stat-value">${r.openPositions || 0} / ${r.maxPositions || '?'}</div>
                ${r.openPairs?.length ? `<div class="stat-detail">${r.openPairs.join(', ')}</div>` : ''}
                ${pendingLabel}
            </div></div>`;

        // Daily P&L
        const dailyCard = `
            <div class="col-md-2 col-6"><div class="stat-card">
                <div class="stat-label">Daily P&L</div>
                <div class="stat-value ${pnlClass(r.dailyPnl)}">${formatUsd(r.dailyPnl)} (${formatPercent(r.dailyPnlPercent)})</div>
                ${r.dailyLimitReached ? '<div class="stat-detail text-danger">\u26a0 Daily limit reached</div>' : ''}
            </div></div>`;

        // Margin gauge
        const marginPct = r.usedMarginPercent || 0;
        const marginColor = marginPct > 60 ? 'var(--accent-red)' : marginPct > 30 ? 'var(--accent-orange)' : 'var(--accent-green)';
        const marginCard = `
            <div class="col-md-2 col-6"><div class="stat-card">
                <div class="stat-label">Margin Used</div>
                <div class="stat-value">${marginPct.toFixed(1)}%</div>
                <div class="risk-gauge" style="margin-top:4px"><div class="risk-gauge-fill" style="width:${Math.min(100, marginPct)}%;background:${marginColor}"></div></div>
                <div class="stat-detail">${formatUsd(r.usedMargin).replace('+', '')} / ${formatUsd(r.totalEquity).replace('+', '')}</div>
            </div></div>`;

        // Drawdown gauge
        const ddPct = r.currentDrawdown || 0;
        const ddColor = ddPct > 10 ? 'var(--accent-red)' : ddPct > 5 ? 'var(--accent-orange)' : 'var(--accent-green)';
        const ddCard = `
            <div class="col-md-2 col-6"><div class="stat-card">
                <div class="stat-label">Drawdown</div>
                <div class="stat-value ${ddPct > 5 ? 'pnl-negative' : ''}">${ddPct.toFixed(1)}% / ${(r.maxDrawdownPercent || 0).toFixed(0)}%</div>
                <div class="risk-gauge" style="margin-top:4px"><div class="risk-gauge-fill" style="width:${Math.min(100, ddPct / (r.maxDrawdownPercent || 20) * 100)}%;background:${ddColor}"></div></div>
                ${r.drawdownCircuitBreaker ? '<div class="stat-detail text-danger">\u26a0 Circuit breaker!</div>' : ''}
            </div></div>`;

        el('risk-stats').innerHTML = balanceCard + posCard + dailyCard + marginCard + ddCard;

        // WS status
        const wsEl = document.getElementById('ws-status');
        if (wsEl) {
            wsEl.textContent = data.wsConnected ? 'WS: ON' : 'WS: OFF';
            wsEl.className = `badge ${data.wsConnected ? 'bg-success' : 'bg-danger'}`;
        }
    } catch (e) { console.error('Risk error:', e); }
}

async function refreshPositions() {
    try {
        const data = await fetchJson('/api/positions');
        const card = el('open-positions-card');
        const tbody = el('open-positions-body');
        const countBadge = el('trades-count-badge');

        if (!data || data.length === 0) {
            card.style.display = 'none';
            if (countBadge) { countBadge.style.display = 'none'; }
            return;
        }

        card.style.display = 'block';
        el('open-positions-count').textContent = `${data.length} open`;
        if (countBadge) {
            countBadge.textContent = data.length;
            countBadge.style.display = 'inline';
        }

        tbody.innerHTML = '';
        for (const p of data) {
            const isLong = p.direction === 'LONG';
            const pnl = isLong
                ? (p.currentPrice - p.entryPrice) / p.entryPrice * 100 * p.leverage
                : (p.entryPrice - p.currentPrice) / p.entryPrice * 100 * p.leverage;
            const posValueUsd = (p.quantity || 0) * (p.entryPrice || 0);
            const pnlUsd = posValueUsd * pnl / 100;
            const tpDist = Math.abs(p.takeProfit - p.entryPrice);
            const progress = tpDist > 0 ? (isLong
                ? (p.currentPrice - p.entryPrice) / tpDist * 100
                : (p.entryPrice - p.currentPrice) / tpDist * 100) : 0;

            const beLabels = ['—', 'L1 (entry)', 'L2 (+25%)', 'L3 (+50%)'];
            const tfSettings = window._tfSettings?.[p.timeframe];
            const timeout = tfSettings?.timeoutCandles || '?';
            const candles = p.candlesElapsed || 0;
            const remaining = typeof timeout === 'number' ? timeout - candles : '?';
            const candleText = remaining <= 0 && typeof remaining === 'number'
                ? `<span class="text-danger">${candles} / ${timeout} (timeout!)</span>`
                : `${candles} / ${timeout} <small class="text-secondary">(${remaining} left)</small>`;

            // SL display: show original crossed out if BE moved it
            let slDisplay = `<span class="text-danger">${formatPrice(p.stopLoss)}</span>`;
            if (p.beLevel > 0 && p.originalStopLoss && Math.abs(p.originalStopLoss - p.stopLoss) / p.entryPrice > 0.001) {
                slDisplay = `<s class="text-secondary small">${formatPrice(p.originalStopLoss)}</s> <span class="text-warning">${formatPrice(p.stopLoss)}</span>`;
            }

            // Lifecycle state
            let stateLabel = '<span class="badge bg-secondary">Running</span>';
            if (p.beLevel > 0) stateLabel = '<span class="badge badge-confident">Protected</span>';

            const marginUsed = posValueUsd / (p.leverage || 1);

            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td class="fw-bold">${p.pair}</td>
                <td>${directionBadge(p.direction)}</td>
                <td><span class="badge bg-secondary">${p.timeframe}</span></td>
                <td class="small">${p.openTimestamp ? timeAgo(p.openTimestamp) : '—'}</td>
                <td>${formatPrice(p.entryPrice)}</td>
                <td>${formatPrice(p.currentPrice)}</td>
                <td>${slDisplay}</td>
                <td class="text-success">${formatPrice(p.takeProfit)}</td>
                <td>x${p.leverage}</td>
                <td class="small">$${posValueUsd.toFixed(0)} <span class="text-secondary">($${marginUsed.toFixed(0)} margin)</span></td>
                <td class="${pnlClass(pnl)}">${pnl >= 0 ? '+' : ''}${pnl.toFixed(2)}% <small>(${pnlUsd >= 0 ? '+' : ''}$${pnlUsd.toFixed(2)})</small></td>
                <td>
                    <div class="risk-gauge" style="width:80px"><div class="risk-gauge-fill" style="width:${Math.min(100, Math.max(0, progress))}%;background:${progress >= 50 ? 'var(--accent-green)' : progress >= 0 ? 'var(--accent-blue, #4a9eff)' : 'var(--accent-red)'}"></div></div>
                    <small>${progress.toFixed(0)}%</small>
                </td>
                <td><span class="badge ${p.beLevel > 0 ? 'badge-confident' : 'bg-secondary'}">${beLabels[p.beLevel || 0]}</span></td>
                <td class="small">${candleText}</td>
                <td>${stateLabel}</td>
                <td><button class="btn btn-sm btn-outline-danger" onclick="closePosition('${p.pair}')"><i class="bi bi-x-lg"></i></button></td>
            `;
            tbody.appendChild(tr);
        }
    } catch (e) { console.error('Positions error:', e); }
}

async function refreshPendingOrders() {
    try {
        const data = await fetchJson('/api/bot/pending-orders');
        const card = el('pending-orders-card');
        const tbody = el('pending-orders-body');

        if (!data || !data.orders || data.orders.length === 0) {
            card.style.display = 'none';
            return;
        }

        card.style.display = 'block';
        el('pending-orders-count').textContent = `${data.orders.length} pending`;

        tbody.innerHTML = '';
        for (const p of data.orders) {
            const remainSec = Math.floor((p.remainingMs || 0) / 1000);
            const mins = Math.floor(remainSec / 60);
            const secs = remainSec % 60;
            const expiryText = remainSec > 0
                ? `${mins}m ${secs}s`
                : '<span class="text-danger">Expiring...</span>';
            const expiryBarPct = p.expiresAt && p.placedAt
                ? Math.max(0, Math.min(100, p.remainingMs / (p.expiresAt - p.placedAt) * 100))
                : 50;
            const expiryColor = expiryBarPct > 50 ? 'var(--accent-blue, #4a9eff)' : expiryBarPct > 20 ? 'var(--accent-orange)' : 'var(--accent-red)';

            const tr = document.createElement('tr');
            tr.className = 'pending-order-row';
            tr.innerHTML = `
                <td class="fw-bold">${p.pair}</td>
                <td>${directionBadge(p.direction)}</td>
                <td><span class="badge bg-secondary">${p.timeframe || '—'}</span></td>
                <td>${formatPrice(p.limitPrice)}</td>
                <td class="text-danger">${formatPrice(p.stopLoss)}</td>
                <td class="text-success">${formatPrice(p.takeProfit)}</td>
                <td>x${p.leverage}</td>
                <td>${p.positionSizeUsd ? '$' + Number(p.positionSizeUsd).toFixed(0) : '—'}</td>
                <td>${p.score?.toFixed(1) || '—'}</td>
                <td>
                    <div class="risk-gauge" style="width:60px"><div class="risk-gauge-fill" style="width:${expiryBarPct}%;background:${expiryColor}"></div></div>
                    <small>${expiryText}</small>
                </td>
                <td><span class="badge badge-pending-fill"><i class="bi bi-hourglass-split me-1"></i>Waiting fill</span></td>
            `;
            tbody.appendChild(tr);
        }
    } catch (e) { console.error('Pending orders error:', e); }
}

async function refreshJournal() {
    try {
        const data = await fetchJson('/api/trades');
        if (!data) return;

        const filter = el('trades-filter')?.value || 'all';
        const filtered = filter === 'all' ? data : data.filter(t => t.status === filter);

        const emptyMsg = el('trades-empty-msg');
        const table = el('trades-table');
        el('trades-total').textContent = `${filtered.length} / ${data.length} entries`;

        if (filtered.length === 0) {
            emptyMsg.style.display = 'block';
            table.style.display = 'none';
            return;
        }
        emptyMsg.style.display = 'none';
        table.style.display = 'table';

        const tbody = el('trades-table-body');
        tbody.innerHTML = '';
        for (const t of filtered.slice(0, 200)) {
            const isRejected = t.status === 'RISK_REJECTED';
            const tr = document.createElement('tr');
            if (isRejected) tr.className = 'trade-row-rejected';
            tr.innerHTML = `
                <td class="small">${formatDate(t.timestamp)}</td>
                <td class="fw-bold">${t.pair}</td>
                <td>${directionBadge(t.direction)}</td>
                <td><span class="badge bg-secondary">${t.timeframe || '—'}</span></td>
                <td>${formatPrice(t.entryPrice)}</td>
                <td class="text-danger">${formatPrice(t.stopLoss)}</td>
                <td class="text-success">${formatPrice(t.takeProfit)}</td>
                <td>x${t.leverage || '—'}</td>
                <td class="small">${t.positionSizeUsd ? '$' + Number(t.positionSizeUsd).toFixed(0) : '—'}</td>
                <td>${t.score?.toFixed(1) || '—'}</td>
                <td>${statusBadge(t.status)}</td>
                <td class="small">${isRejected ? renderRejections(t.errorMessage) : (t.fillPrice ? formatPrice(t.fillPrice) : t.exchange || '—')}</td>
            `;
            tbody.appendChild(tr);
        }
    } catch (e) { console.error('Journal error:', e); }
}

function renderRejections(msg) {
    if (!msg) return '';
    return '<div class="rejection-reasons">' +
        msg.split('; ').map(r => `<span class="rejection-pill">${r}</span>`).join('') +
        '</div>';
}

// ==================== HISTORY TAB ====================

export async function refreshHistory() {
    try {
        const [trades, summary] = await Promise.all([
            fetchJson('/api/history'),
            fetchJson('/api/history/summary')
        ]);

        window._historyData = trades || [];
        renderHistorySummary(summary);
        renderHistoryTable(trades);

        const badge = el('history-count-badge');
        if (badge && trades?.length) {
            badge.textContent = trades.length;
            badge.style.display = 'inline';
        }
    } catch (e) { console.error('History error:', e); }
}

function renderHistorySummary(s) {
    if (!s) return;
    el('history-stats').innerHTML = `
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Total Trades</div><div class="stat-value">${s.totalTrades}</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Win Rate</div><div class="stat-value">${s.winRate?.toFixed(1) || 0}%</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Total P&L</div><div class="stat-value ${pnlClass(s.totalPnlUsd)}">${formatUsd(s.totalPnlUsd)}</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Avg P&L %</div><div class="stat-value ${pnlClass(s.avgPnlPercent)}">${formatPercent(s.avgPnlPercent)}</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Best</div><div class="stat-value pnl-positive">${formatPercent(s.bestTradePnl)}</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Worst</div><div class="stat-value pnl-negative">${formatPercent(s.worstTradePnl)}</div></div></div>
    `;
}

export function renderHistoryTable(trades) {
    if (!trades) return;
    const filter = el('history-filter')?.value || 'all';
    const filtered = filter === 'all' ? trades : trades.filter(t => t.closeReason === filter);

    const emptyMsg = el('history-empty-msg');
    const table = el('history-table');
    el('history-total').textContent = `${filtered.length} trades`;

    if (filtered.length === 0) {
        emptyMsg.style.display = 'block';
        table.style.display = 'none';
        return;
    }
    emptyMsg.style.display = 'none';
    table.style.display = 'table';

    const tbody = el('history-table-body');
    tbody.innerHTML = '';

    for (const t of filtered) {
        const tr = document.createElement('tr');
        // Duration
        let duration = '—';
        if (t.openDate && t.closeDate) {
            try {
                const open = new Date(t.openDate.replace(' ', 'T'));
                const close = new Date(t.closeDate.replace(' ', 'T'));
                const diffMs = close - open;
                const mins = Math.floor(diffMs / 60000);
                if (mins < 60) duration = `${mins}m`;
                else if (mins < 1440) duration = `${Math.floor(mins / 60)}h ${mins % 60}m`;
                else duration = `${Math.floor(mins / 1440)}d ${Math.floor((mins % 1440) / 60)}h`;
            } catch (_) { /* keep default */ }
        }
        tr.innerHTML = `
            <td class="small">${t.openDate || '—'}</td>
            <td class="small">${t.closeDate || '—'}</td>
            <td class="small">${duration}</td>
            <td class="fw-bold">${t.pair}</td>
            <td>${directionBadge(t.direction)}</td>
            <td><span class="badge bg-secondary">${t.timeframe || '—'}</span></td>
            <td>${formatPrice(t.entryPrice)}</td>
            <td>${formatPrice(t.exitPrice)}</td>
            <td class="text-danger">${formatPrice(t.stopLoss)}</td>
            <td class="text-success">${formatPrice(t.takeProfit)}</td>
            <td>x${t.leverage || '—'}</td>
            <td class="small">${t.positionSizeUsd ? '$' + Number(t.positionSizeUsd).toFixed(0) : '—'}</td>
            <td class="${pnlClass(t.pnlPercent)}">${formatPercent(t.pnlPercent)}</td>
            <td class="${pnlClass(t.pnlUsd)}">${formatUsd(t.pnlUsd)}</td>
            <td class="${t.feeUsd != null && t.feeUsd < 0 ? 'pnl-positive' : 'pnl-negative'}">${t.feeUsd != null ? (t.feeUsd <= 0 ? '+' : '-') + Math.abs(t.feeUsd).toFixed(2) + '$' : '—'}</td>
            <td>${reasonBadge(t.closeReason)}</td>
            <td>${t.breakEvenApplied ? '<span class="badge badge-confident">BE</span>' : '—'}</td>
        `;
        tbody.appendChild(tr);
    }
}

// ==================== CLOSE POSITION ====================

async function closePosition(pair) {
    if (!confirm(`Close position on ${pair}?`)) return;
    try {
        await fetch(`/api/close-position/${pair}`, { method: 'POST' });
        refreshPositions();
    } catch (e) { console.error('Close error:', e); }
}

window.closePosition = closePosition;
window.renderHistoryTable = renderHistoryTable;
