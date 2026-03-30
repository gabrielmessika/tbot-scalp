// Backtest tab rendering
import { formatPrice, pnlClass, el } from './utils.js';

let selectedSession = 0;
let selectedPortfolio = 0;
let backtestData = null;

export function renderBacktest(bt) {
    backtestData = bt;
    if (!bt || !bt.sessions || bt.sessions.length === 0) {
        el('session-selector').innerHTML = '';
        el('portfolio-selector').innerHTML = '';
        el('comparison-card').style.display = 'none';
        el('backtest-stats').innerHTML = '';
        el('btn-export-csv').style.display = 'none';
        return;
    }

    // Session selector
    const sessionSel = el('session-selector');
    sessionSel.innerHTML = '';
    bt.sessions.forEach((session, i) => {
        const btn = document.createElement('button');
        btn.className = `btn btn-sm ${i === selectedSession ? 'btn-primary' : 'btn-outline-secondary'}`;
        btn.textContent = session.sessionName + ` (${session.totalSignals} sig.)`;
        if (session.sessionDescription) btn.title = session.sessionDescription;
        btn.onclick = () => { selectedSession = i; renderBacktest(bt); };
        sessionSel.appendChild(btn);
    });

    const session = bt.sessions[selectedSession];
    if (!session?.portfolios?.length) return;

    // Portfolio selector
    const portSel = el('portfolio-selector');
    portSel.innerHTML = '';
    session.portfolios.forEach((p, i) => {
        const btn = document.createElement('button');
        btn.className = `btn btn-sm ${i === selectedPortfolio ? 'btn-primary' : 'btn-outline-secondary'}`;
        btn.textContent = `${p.initialBalance.toLocaleString('en-US')}$`;
        btn.onclick = () => { selectedPortfolio = i; renderBacktest(bt); };
        portSel.appendChild(btn);
    });

    renderComparisonTable(session);
    const pf = session.portfolios[selectedPortfolio];
    if (!pf) return;
    renderPortfolioStats(pf);
    renderPortfolioTrades(pf);

    el('btn-export-csv').style.display = bt.sessions.some(s => s.portfolios.some(p => p.trades?.length)) ? 'inline-block' : 'none';
}

function renderComparisonTable(session) {
    el('comparison-card').style.display = 'block';
    const tbody = el('comparison-body');
    tbody.innerHTML = '';

    for (const pf of session.portfolios) {
        const cls = pnlClass(pf.roi);
        const sign = pf.roi >= 0 ? '+' : '';
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td class="fw-bold">${pf.initialBalance.toLocaleString('en-US')}$</td>
            <td>${pf.initialBalance.toLocaleString('en-US')}$</td>
            <td class="${cls}">${pf.finalBalance.toLocaleString('en-US', { minimumFractionDigits: 2 })}$</td>
            <td class="${cls}">${sign}${pf.roi.toFixed(2)}%</td>
            <td>${pf.winRate.toFixed(1)}%</td>
            <td>${pf.totalTrades}</td>
            <td class="pnl-positive">${pf.wins}</td>
            <td class="pnl-negative">${pf.losses}</td>
            <td>${pf.skipped}</td>
            <td class="pnl-negative">${pf.maxDrawdown.toFixed(2)}%</td>
            <td class="pnl-positive">+${pf.bestTrade.toFixed(2)}$</td>
            <td class="pnl-negative">${pf.worstTrade.toFixed(2)}$</td>
        `;
        tbody.appendChild(tr);
    }
}

function renderPortfolioStats(pf) {
    const cls = pnlClass(pf.roi);
    const sign = pf.roi >= 0 ? '+' : '';
    el('backtest-stats').innerHTML = `
        <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Initial Balance</div><div class="stat-value">${pf.initialBalance.toLocaleString('en-US')}$</div></div></div>
        <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Final Balance</div><div class="stat-value ${cls}">${pf.finalBalance.toLocaleString('en-US', { minimumFractionDigits: 2 })}$</div></div></div>
        <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">ROI</div><div class="stat-value ${cls}">${sign}${pf.roi.toFixed(2)}%</div></div></div>
        <div class="col-md-3 col-6"><div class="stat-card"><div class="stat-label">Win Rate</div><div class="stat-value">${pf.winRate.toFixed(1)}%</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Trades</div><div class="stat-value">${pf.totalTrades}</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Wins</div><div class="stat-value pnl-positive">${pf.wins}</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Losses</div><div class="stat-value pnl-negative">${pf.losses}</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Skipped</div><div class="stat-value text-warning">${pf.skipped}</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Max DD</div><div class="stat-value pnl-negative">${pf.maxDrawdown.toFixed(2)}%</div></div></div>
        <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Best/Worst</div><div class="stat-value" style="font-size:1rem"><span class="pnl-positive">+${pf.bestTrade.toFixed(2)}$</span> / <span class="pnl-negative">${pf.worstTrade.toFixed(2)}$</span></div></div></div>
    `;
}

function renderPortfolioTrades(pf) {
    const tbody = el('trades-body');
    tbody.innerHTML = '';
    el('trade-count').textContent = `${pf.trades.length} trades (${pf.skipped} skipped)`;

    pf.trades.forEach((t, i) => {
        const dirCls = t.direction === 'LONG' ? 'badge-long' : 'badge-short';
        const resCls = t.result?.includes('TP') || t.result?.includes('✅') ? 'badge-tp'
            : t.result?.includes('SL') || t.result?.includes('❌') ? 'badge-sl'
                : t.result?.includes('TIMEOUT') ? 'badge-timeout'
                    : t.result?.includes('TREND') ? 'badge-trend'
                        : 'badge-pending';
        const pCls = pnlClass(t.pnl);
        const rowCls = t.skipped ? 'opacity-50' : '';

        const entryDate = t.entryTime ? new Date(t.entryTime).toLocaleString('en-US', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-';
        const exitDate = t.exitTime ? new Date(t.exitTime).toLocaleString('en-US', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-';

        const tr = document.createElement('tr');
        tr.className = rowCls;
        tr.innerHTML = `
            <td>${i + 1}</td>
            <td class="fw-bold">${t.pair}</td>
            <td><span class="badge bg-secondary">${t.timeframe}</span></td>
            <td><span class="badge ${dirCls}">${t.direction}</span></td>
            <td class="small">${(t.strategies || []).join(', ')}</td>
            <td>${formatPrice(t.entryPrice)}</td>
            <td class="text-danger">${formatPrice(t.stopLoss)}</td>
            <td class="text-success">${formatPrice(t.takeProfit)}</td>
            <td>${t.skipped ? '-' : formatPrice(t.exitPrice)}</td>
            <td>x${t.leverage}</td>
            <td><span class="badge ${resCls}">${(t.result || '').replace(/[✅❌⏰📈🔁]/g, '').trim()}</span></td>
            <td class="${pCls}">${t.pnl >= 0 ? '+' : ''}${t.pnl.toFixed(2)}$</td>
            <td>${t.balanceBeforeTrade.toLocaleString('en-US', { minimumFractionDigits: 2 })}$</td>
            <td class="small">${entryDate}</td>
            <td class="small">${exitDate}</td>
            <td>${t.score}</td>
        `;
        tbody.appendChild(tr);
    });
}

export function exportBacktestCSV() {
    if (!backtestData?.sessions) return;
    const bt = backtestData;
    const sep = ';';
    const rows = [];

    rows.push(['=== OVERVIEW ==='], [],
        ['Session', 'Portfolio', 'Initial', 'Final', 'ROI(%)', 'WR(%)', 'Trades', 'Wins', 'Losses', 'Skipped', 'MaxDD(%)', 'Best($)', 'Worst($)']);

    for (const s of bt.sessions) {
        for (const p of s.portfolios) {
            rows.push([s.sessionName, p.initialBalance + '$', p.initialBalance.toFixed(2), p.finalBalance.toFixed(2),
            p.roi.toFixed(2), p.winRate.toFixed(1), p.totalTrades, p.wins, p.losses, p.skipped,
            p.maxDrawdown.toFixed(2), p.bestTrade.toFixed(2), p.worstTrade.toFixed(2)]);
        }
    }

    // Strategy breakdown
    rows.push([], [], ['=== BY STRATEGY ==='], []);
    const stratMap = {};
    for (const s of bt.sessions) for (const p of s.portfolios) for (const t of p.trades) {
        if (t.skipped) continue;
        const key = (t.strategies || []).sort().join('+');
        if (!stratMap[key]) stratMap[key] = { w: 0, l: 0, pnl: 0, n: 0 };
        stratMap[key].n++; stratMap[key].pnl += t.pnl;
        if (t.result?.includes('✅') || t.result?.includes('TP')) stratMap[key].w++;
        else if (t.result?.includes('❌') || t.result?.includes('SL')) stratMap[key].l++;
    }
    rows.push(['Strategy', 'Trades', 'Wins', 'Losses', 'WR(%)', 'P&L($)', 'Avg($)']);
    Object.entries(stratMap).sort((a, b) => b[1].pnl - a[1].pnl).forEach(([k, v]) => {
        const wr = (v.w + v.l) > 0 ? (v.w / (v.w + v.l) * 100).toFixed(1) : '0';
        rows.push([k, v.n, v.w, v.l, wr, v.pnl.toFixed(2), (v.pnl / v.n).toFixed(2)]);
    });

    // Pair breakdown
    rows.push([], [], ['=== BY PAIR ==='], []);
    const pairMap = {};
    for (const s of bt.sessions) for (const p of s.portfolios) for (const t of p.trades) {
        if (t.skipped) continue;
        if (!pairMap[t.pair]) pairMap[t.pair] = { w: 0, l: 0, pnl: 0, n: 0 };
        pairMap[t.pair].n++; pairMap[t.pair].pnl += t.pnl;
        if (t.result?.includes('✅') || t.result?.includes('TP')) pairMap[t.pair].w++;
        else if (t.result?.includes('❌') || t.result?.includes('SL')) pairMap[t.pair].l++;
    }
    rows.push(['Pair', 'Trades', 'Wins', 'Losses', 'WR(%)', 'P&L($)', 'Avg($)']);
    Object.entries(pairMap).sort((a, b) => b[1].pnl - a[1].pnl).forEach(([k, v]) => {
        const wr = (v.w + v.l) > 0 ? (v.w / (v.w + v.l) * 100).toFixed(1) : '0';
        rows.push([k, v.n, v.w, v.l, wr, v.pnl.toFixed(2), (v.pnl / v.n).toFixed(2)]);
    });

    const BOM = '\uFEFF';
    const csv = BOM + rows.map(r => r.join(sep)).join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `backtest_scalp_${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
}

window.exportBacktestCSV = exportBacktestCSV;
