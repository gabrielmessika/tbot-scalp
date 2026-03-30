// Countdown timer module
import { el } from './utils.js';

let countdownInterval = null;
let nextRefreshTime = 0;

export function startCountdown(seconds) {
    nextRefreshTime = Date.now() + seconds * 1000;
    if (countdownInterval) clearInterval(countdownInterval);
    countdownInterval = setInterval(updateDisplay, 1000);
    updateDisplay();
}

function updateDisplay() {
    const remaining = Math.max(0, Math.round((nextRefreshTime - Date.now()) / 1000));
    const display = el('next-refresh');
    if (display) {
        if (remaining > 0) {
            const m = Math.floor(remaining / 60);
            const s = remaining % 60;
            display.textContent = `Next refresh: ${m}:${s.toString().padStart(2, '0')}`;
        } else {
            display.textContent = 'Refreshing...';
        }
    }
}

export function stopCountdown() {
    if (countdownInterval) {
        clearInterval(countdownInterval);
        countdownInterval = null;
    }
    const display = el('next-refresh');
    if (display) display.textContent = '';
}
