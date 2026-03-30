// Trading tab: open positions, trade journal, history, risk
import { formatPrice, formatUsd, formatPercent, pnlClass, directionBadge, reasonBadge, statusBadge, formatDate, timeAgo, fetchJson, el } from './utils.js';

// ==================== TRADES TAB ====================

export async function refreshTrades() {
    await Promise.all([refreshExecStatus(), refreshRisk(), refreshPositions(), refreshJournal()]);
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
        el('risk-stats').innerHTML = `
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Balance</div><div class="stat-value">${formatUsd(r.availableBalance).replace('+', '')}</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Equity</div><div class="stat-value">${formatUsd(r.totalEquity).replace('+', '')}</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Daily P&L</div><div class="stat-value ${pnlClass(r.dailyPnl)}">${formatUsd(r.dailyPnl)}</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Daily P&L %</div><div class="stat-value ${pnlClass(r.dailyPnlPercent)}">${formatPercent(r.dailyPnlPercent)}</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Drawdown</div><div class="stat-value ${r.currentDrawdown > 5 ? 'pnl-negative' : ''}">${formatPercent(r.currentDrawdown, 2)}</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Peak Equity</div><div class="stat-value">${formatUsd(r.peakEquity).replace('+', '')}</div></div></div>
        `;

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
            const tpDist = Math.abs(p.takeProfit - p.entryPrice);
            const progress = tpDist > 0 ? (isLong
                ? (p.currentPrice - p.entryPrice) / tpDist * 100
                : (p.entryPrice - p.currentPrice) / tpDist * 100) : 0;

            const beLabels = ['—', 'L1 (entry)', 'L2 (+25%)', 'L3 (+50%)'];

            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td class="fw-bold">${p.pair}</td>
                <td>${directionBadge(p.direction)}</td>
                <td><span class="badge bg-secondary">${p.timeframe}</span></td>
                <td>${formatPrice(p.entryPrice)}</td>
                <td>${formatPrice(p.currentPrice)}</td>
                <td class="text-danger">${formatPrice(p.stopLoss)}</td>
                <td class="text-success">${formatPrice(p.takeProfit)}</td>
                <td>x${p.leverage}</td>
                <td class="${pnlClass(pnl)}">${pnl >= 0 ? '+' : ''}${pnl.toFixed(2)}%</td>
                <td>
                    <div class="risk-gauge" style="width:80px"><div class="risk-gauge-fill" style="width:${Math.min(100, Math.max(0, progress))}%;background:${progress >= 50 ? 'var(--accent-green)' : 'var(--accent-orange)'}"></div></div>
                    <small>${progress.toFixed(0)}%</small>
                </td>
                <td><span class="badge ${p.beLevel > 0 ? 'badge-confident' : 'bg-secondary'}">${beLabels[p.beLevel || 0]}</span></td>
                <td>${p.candlesElapsed || 0}</td>
                <td><button class="btn btn-sm btn-outline-danger" onclick="closePosition('${p.pair}')"><i class="bi bi-x-lg"></i></button></td>
            `;
            tbody.appendChild(tr);
        }
    } catch (e) { console.error('Positions error:', e); }
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
                <td>${t.score?.toFixed(1) || '—'}</td>
                <td>${statusBadge(t.status)}</td>
                <td class="small">${isRejected ? renderRejections(t.errorMessage) : (t.exchange || '—')}</td>
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
        tr.innerHTML = `
            <td class="small">${t.openDate || '—'}</td>
            <td class="small">${t.closeDate || '—'}</td>
            <td class="fw-bold">${t.pair}</td>
            <td>${directionBadge(t.direction)}</td>
            <td><span class="badge bg-secondary">${t.timeframe || '—'}</span></td>
            <td>${formatPrice(t.entryPrice)}</td>
            <td>${formatPrice(t.exitPrice)}</td>
            <td>x${t.leverage || '—'}</td>
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
