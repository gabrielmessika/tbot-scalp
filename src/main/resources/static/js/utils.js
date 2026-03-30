// Utility functions shared across modules

export function formatPrice(price) {
    if (price == null) return '—';
    const n = Number(price);
    if (isNaN(n)) return '—';
    if (n >= 100) return n.toFixed(2);
    if (n >= 1) return n.toFixed(4);
    return n.toFixed(6);
}

export function formatUsd(val) {
    if (val == null) return '—';
    const n = Number(val);
    if (isNaN(n)) return '—';
    const sign = n >= 0 ? '+' : '';
    return sign + '$' + n.toFixed(2);
}

export function formatPercent(val, decimals = 1) {
    if (val == null) return '—';
    const n = Number(val);
    if (isNaN(n)) return '—';
    const sign = n >= 0 ? '+' : '';
    return sign + n.toFixed(decimals) + '%';
}

export function pnlClass(val) {
    const n = Number(val);
    if (n > 0) return 'pnl-positive';
    if (n < 0) return 'pnl-negative';
    return 'pnl-neutral';
}

export function directionBadge(dir) {
    if (dir === 'LONG') return '<span class="badge badge-long">LONG</span>';
    if (dir === 'SHORT') return '<span class="badge badge-short">SHORT</span>';
    return `<span class="badge bg-secondary">${dir || '—'}</span>`;
}

export function reasonBadge(reason) {
    const map = {
        'TP_HIT': 'badge-tp',
        'SL_HIT': 'badge-sl',
        'TIMEOUT': 'badge-timeout',
        'TREND_REVERSAL': 'badge-trend',
        'CONTRARY_SIGNALS': 'bg-purple',
        'MANUAL': 'badge-pending',
        'MANUAL_CLOSE': 'badge-pending',
    };
    const cls = map[reason] || 'badge-pending';
    return `<span class="badge ${cls}">${reason || 'UNKNOWN'}</span>`;
}

export function statusBadge(status) {
    const map = {
        'DRY_RUN': 'badge-dryrun',
        'FILLED': 'badge-filled',
        'RISK_REJECTED': 'badge-risk-rejected',
        'REJECTED': 'badge-rejected',
    };
    const cls = map[status] || 'bg-secondary';
    return `<span class="badge ${cls}">${status || '—'}</span>`;
}

export function formatDate(ts) {
    if (!ts) return '—';
    if (typeof ts === 'string' && ts.includes('-')) return ts;
    const d = new Date(Number(ts));
    return d.toLocaleString('sv-SE').replace('T', ' ');
}

export function timeAgo(ts) {
    if (!ts) return '';
    const ms = Date.now() - Number(ts);
    const mins = Math.floor(ms / 60000);
    if (mins < 60) return `${mins}m ago`;
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return `${hrs}h ${mins % 60}m ago`;
    return `${Math.floor(hrs / 24)}d ago`;
}

export async function fetchJson(url) {
    const res = await fetch(url);
    if (res.status === 204) return null;
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
}

export function el(id) {
    return document.getElementById(id);
}
