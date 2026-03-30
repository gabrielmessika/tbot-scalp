// Logs tab module
import { el, fetchJson } from './utils.js';

export async function refreshLogs() {
    const lines = el('log-lines')?.value || 200;
    const filter = el('log-filter')?.value || '';
    const url = `/api/logs?lines=${lines}` + (filter ? `&filter=${encodeURIComponent(filter)}` : '');
    try {
        const data = await fetchJson(url);
        if (!data) return;
        const output = el('log-output');
        if (!output) return;

        output.innerHTML = data.map(line => {
            let cls = '';
            if (line.includes(' ERROR ')) cls = 'log-ERROR';
            else if (line.includes(' WARN ')) cls = 'log-WARN';
            else if (line.includes('[SIGNAL]') || line.includes('[ORDER]') || line.includes('[POSITION]')) cls = 'log-SIGNAL';
            else cls = 'log-INFO';
            // Escape HTML
            const safe = line.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            return `<span class="${cls}">${safe}</span>`;
        }).join('\n');

        if (el('log-auto-scroll')?.checked) {
            output.scrollTop = output.scrollHeight;
        }
    } catch (e) {
        console.error('Log fetch error:', e);
    }
}

export function initLogs() {
    el('log-filter')?.addEventListener('input', () => {
        clearTimeout(window._logFilterTimeout);
        window._logFilterTimeout = setTimeout(refreshLogs, 500);
    });
    el('log-lines')?.addEventListener('change', refreshLogs);
}

// Expose for onclick
window.refreshLogs = refreshLogs;
