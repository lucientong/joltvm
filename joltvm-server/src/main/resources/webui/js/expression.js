/**
 * JoltVM Web IDE — Expression (OGNL) Panel
 * Copyright 2026 lucientong. Apache License 2.0
 */
const ExpressionPanel = (() => {
    'use strict';

    const HISTORY_KEY = 'joltvm_ognl_history';
    const MAX_HISTORY = 50;

    function getHistory() {
        try {
            return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]');
        } catch { return []; }
    }

    function addHistory(expr) {
        let history = getHistory().filter(h => h !== expr);
        history.unshift(expr);
        if (history.length > MAX_HISTORY) history = history.slice(0, MAX_HISTORY);
        localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
        renderHistory();
    }

    function renderHistory() {
        const list = document.getElementById('ognlHistory');
        if (!list) return;
        const history = getHistory();
        list.innerHTML = history.length === 0
            ? '<li class="placeholder-msg">No history yet</li>'
            : history.map(h => `<li class="ognl-history-item" title="${escapeHtml(h)}">${escapeHtml(truncate(h, 80))}</li>`).join('');

        list.querySelectorAll('.ognl-history-item').forEach((li, i) => {
            li.addEventListener('click', () => {
                document.getElementById('ognlExpression').value = history[i];
            });
        });
    }

    async function evaluate() {
        const textarea = document.getElementById('ognlExpression');
        const expression = textarea.value.trim();
        if (!expression) return;

        const resultPanel = document.getElementById('ognlResult');
        const statusEl = document.getElementById('ognlStatus');
        resultPanel.innerHTML = '<div class="placeholder-msg">Evaluating...</div>';
        statusEl.textContent = '';

        const depthInput = document.getElementById('ognlDepth');
        const depth = parseInt(depthInput?.value) || 5;

        const res = await JoltAPI.ognlEval(expression, depth);
        addHistory(expression);

        if (!res.ok) {
            resultPanel.innerHTML = `<div class="error-msg">HTTP Error: ${res.status}</div>`;
            return;
        }

        const data = res.data;
        if (data.success) {
            statusEl.className = 'status-msg success';
            statusEl.textContent = `${data.type} — ${data.execTimeMs}ms`;
            resultPanel.innerHTML = `<pre class="ognl-result-json">${escapeHtml(JSON.stringify(data.result, null, 2))}</pre>`;
        } else {
            const isSecurityError = data.errorType === 'SECURITY';
            statusEl.className = 'status-msg error';
            statusEl.textContent = `${data.errorType} — ${data.execTimeMs}ms`;
            resultPanel.innerHTML = `<div class="${isSecurityError ? 'error-msg security-error' : 'error-msg'}">${escapeHtml(data.error)}</div>`;
        }
    }

    function escapeHtml(text) {
        if (!text) return '';
        const el = document.createElement('span');
        el.textContent = text;
        return el.innerHTML;
    }

    function truncate(str, max) {
        return str && str.length > max ? str.substring(0, max) + '...' : str;
    }

    /** Preset templates for common diagnostics */
    const PRESETS = [
        { label: 'JVM Memory', expr: '#runtime.freeMemory() + " / " + #runtime.totalMemory() + " / " + #runtime.maxMemory()' },
        { label: 'Available CPUs', expr: '#runtime.availableProcessors()' },
        { label: 'Java Version', expr: '#runtime.javaVersion()' },
        { label: 'OS Name', expr: '#runtime.osName()' },
        { label: 'Current Time', expr: '#runtime.currentTimeMillis()' },
        { label: '1 + 1', expr: '1 + 1' },
        { label: 'String manipulation', expr: '"Hello JoltVM".substring(6).toUpperCase()' },
    ];

    function renderPresets() {
        const container = document.getElementById('ognlPresets');
        if (!container) return;
        container.innerHTML = PRESETS.map(p =>
            `<button class="btn btn-sm ognl-preset" title="${escapeHtml(p.expr)}">${escapeHtml(p.label)}</button>`
        ).join(' ');
        container.querySelectorAll('.ognl-preset').forEach((btn, i) => {
            btn.addEventListener('click', () => {
                document.getElementById('ognlExpression').value = PRESETS[i].expr;
            });
        });
    }

    function init() {
        document.getElementById('ognlEvalBtn')?.addEventListener('click', evaluate);
        document.getElementById('ognlExpression')?.addEventListener('keydown', (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                evaluate();
            }
        });
        document.getElementById('ognlClearBtn')?.addEventListener('click', () => {
            document.getElementById('ognlExpression').value = '';
            document.getElementById('ognlResult').innerHTML = '<div class="placeholder-msg">Enter an expression and press Ctrl+Enter or click Execute</div>';
            document.getElementById('ognlStatus').textContent = '';
        });
        renderPresets();
        renderHistory();
    }

    return { init, evaluate };
})();

// Auto-init on DOM ready
document.addEventListener('DOMContentLoaded', () => ExpressionPanel.init());
