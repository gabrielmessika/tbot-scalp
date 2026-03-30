// Stats tab module
import { formatUsd, formatPercent, pnlClass, el, fetchJson } from './utils.js';

let statsData = null;
let selectedPeriod = '30d';
let pnlChart = null;
let wrChart = null;

export async function refreshStats() {
    try {
        const data = await fetchJson('/api/stats');
        if (!data || data.message) {
            el('stats-summary').innerHTML = '<div class="col-12 text-center text-secondary py-4"><i class="bi bi-inbox" style="font-size:2rem"></i><p class="mt-2">No trade history available for stats.</p></div>';
            el('stats-charts-row').style.display = 'none';
            return;
        }
        statsData = data;
        renderPeriodSelector();
        renderStats();
    } catch (e) { console.error('Stats error:', e); }
}

function renderPeriodSelector() {
    if (!statsData?.periods) return;
    const sel = el('stats-period-selector');
    sel.innerHTML = '';
    for (const period of Object.keys(statsData.periods)) {
        const btn = document.createElement('button');
        btn.className = `btn btn-sm ${period === selectedPeriod ? 'btn-primary' : 'btn-outline-secondary'}`;
        btn.textContent = period;
        btn.onclick = () => { selectedPeriod = period; renderStats(); };
        sel.appendChild(btn);
    }
}

function renderStats() {
    if (!statsData) return;

    // Summary cards for selected period
    const ps = statsData.periods?.[selectedPeriod];
    if (ps) {
        el('stats-summary').innerHTML = `
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Trades</div><div class="stat-value">${ps.totalTrades}</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Win Rate</div><div class="stat-value">${ps.winRate}%</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Total P&L</div><div class="stat-value ${pnlClass(ps.totalPnlUsd)}">${formatUsd(ps.totalPnlUsd)}</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Avg P&L</div><div class="stat-value ${pnlClass(ps.avgPnlUsd)}">${formatUsd(ps.avgPnlUsd)}</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Profit Factor</div><div class="stat-value">${ps.profitFactor || '—'}</div></div></div>
            <div class="col-md-2 col-4"><div class="stat-card"><div class="stat-label">Best / Worst</div><div class="stat-value" style="font-size:1rem"><span class="pnl-positive">${formatPercent(ps.bestTrade)}</span> / <span class="pnl-negative">${formatPercent(ps.worstTrade)}</span></div></div></div>
        `;
    }

    // Breakdown tables
    renderBreakdownTable('stats-by-pair', 'By Pair', statsData.byPair);
    renderBreakdownTable('stats-by-timeframe', 'By Timeframe', statsData.byTimeframe);
    renderBreakdownTable('stats-by-direction', 'By Direction', statsData.byDirection);
    renderBreakdownTable('stats-by-reason', 'By Close Reason', statsData.byCloseReason);

    // Charts
    renderCharts();
}

function renderBreakdownTable(containerId, title, data) {
    const container = el(containerId);
    if (!data || Object.keys(data).length === 0) {
        container.innerHTML = '';
        return;
    }

    const entries = Object.entries(data).sort((a, b) => (b[1].totalPnlUsd || 0) - (a[1].totalPnlUsd || 0));

    let html = `<div class="card-header fw-bold"><i class="bi bi-bar-chart me-2"></i>${title}</div>
        <div class="card-body p-0"><div class="table-responsive"><table class="table table-hover table-sm mb-0">
        <thead><tr><th>${title.replace('By ', '')}</th><th>Trades</th><th>W/L</th><th>WR%</th><th>P&L $</th><th>Avg $</th><th>PF</th></tr></thead><tbody>`;

    for (const [key, s] of entries) {
        html += `<tr>
            <td class="fw-bold">${key}</td>
            <td>${s.totalTrades}</td>
            <td><span class="pnl-positive">${s.wins}</span>/<span class="pnl-negative">${s.losses}</span></td>
            <td>${s.winRate}%</td>
            <td class="${pnlClass(s.totalPnlUsd)}">${formatUsd(s.totalPnlUsd)}</td>
            <td class="${pnlClass(s.avgPnlUsd)}">${formatUsd(s.avgPnlUsd)}</td>
            <td>${s.profitFactor || '—'}</td>
        </tr>`;
    }
    html += '</tbody></table></div></div>';
    container.innerHTML = html;
}

function renderCharts() {
    if (typeof Chart === 'undefined') return;
    el('stats-charts-row').style.display = 'flex';

    // P&L by pair bar chart
    const pairData = statsData.byPair;
    if (pairData) {
        const labels = Object.keys(pairData);
        const values = labels.map(k => pairData[k].totalPnlUsd || 0);
        const colors = values.map(v => v >= 0 ? 'rgba(63, 185, 80, 0.7)' : 'rgba(248, 81, 73, 0.7)');

        if (pnlChart) pnlChart.destroy();
        pnlChart = new Chart(el('stats-pnl-chart'), {
            type: 'bar',
            data: {
                labels,
                datasets: [{ label: 'P&L ($)', data: values, backgroundColor: colors, borderWidth: 0 }]
            },
            options: {
                responsive: true,
                plugins: { legend: { display: false } },
                scales: {
                    x: { ticks: { color: '#8b949e', font: { size: 10 } }, grid: { color: 'rgba(48,54,61,0.5)' } },
                    y: { ticks: { color: '#8b949e' }, grid: { color: 'rgba(48,54,61,0.5)' } }
                }
            }
        });
    }

    // Win rate by pair horizontal bar
    if (pairData) {
        const labels = Object.keys(pairData).filter(k => pairData[k].totalTrades >= 2);
        const values = labels.map(k => pairData[k].winRate || 0);
        const colors = values.map(v => v >= 50 ? 'rgba(63, 185, 80, 0.7)' : v >= 35 ? 'rgba(210, 153, 34, 0.7)' : 'rgba(248, 81, 73, 0.7)');

        if (wrChart) wrChart.destroy();
        wrChart = new Chart(el('stats-wr-chart'), {
            type: 'bar',
            data: {
                labels,
                datasets: [{ label: 'Win Rate %', data: values, backgroundColor: colors, borderWidth: 0 }]
            },
            options: {
                indexAxis: 'y',
                responsive: true,
                plugins: { legend: { display: false } },
                scales: {
                    x: { max: 100, ticks: { color: '#8b949e' }, grid: { color: 'rgba(48,54,61,0.5)' } },
                    y: { ticks: { color: '#8b949e', font: { size: 10 } }, grid: { display: false } }
                }
            }
        });
    }
}
