/**
 * JoltVM Web IDE — Loggers Panel
 * Copyright 2026 lucientong. Apache License 2.0
 */
const LoggersPanel = (() => {
    'use strict';

    let allLoggers = [];

    async function loadLoggers() {
        const container = document.getElementById('loggerTableBody');
        container.innerHTML = '<tr><td colspan="4">Loading loggers...</td></tr>';

        const res = await JoltAPI.loggerList();
        if (!res.ok) {
            container.innerHTML = '<tr><td colspan="4" class="error-msg">Failed to load loggers</td></tr>';
            return;
        }

        document.getElementById('loggerFramework').textContent = res.data.framework;
        document.getElementById('loggerTotalCount').textContent = `${res.data.count} loggers`;
        allLoggers = res.data.loggers;
        renderLoggers();
    }

    function renderLoggers() {
        const search = (document.getElementById('loggerSearch')?.value || '').toLowerCase();
        const tbody = document.getElementById('loggerTableBody');

        const filtered = allLoggers.filter(l =>
            !search || (l.name && l.name.toLowerCase().includes(search))
        );

        if (filtered.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4">No loggers match the filter</td></tr>';
            return;
        }

        const LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF'];

        tbody.innerHTML = filtered.map(l => {
            const options = LEVELS.map(lv => {
                const selected = (l.effectiveLevel === lv || l.level === lv) ? ' selected' : '';
                return `<option value="${lv}"${selected}>${lv}</option>`;
            }).join('');

            const levelDisplay = l.level
                ? `<span class="level-badge level-${l.level}">${l.level}</span>`
                : `<span class="level-badge level-inherited">${l.effectiveLevel || '?'} (inherited)</span>`;

            return `<tr>
                <td title="${escapeHtml(l.name)}">${escapeHtml(truncate(l.name, 80))}</td>
                <td>${levelDisplay}</td>
                <td>${l.effectiveLevel || '-'}</td>
                <td>
                    <select class="logger-level-select" data-name="${escapeHtml(l.name)}">
                        ${options}
                    </select>
                    <button class="btn btn-sm logger-set-btn" data-name="${escapeHtml(l.name)}">Set</button>
                </td>
            </tr>`;
        }).join('');

        // Wire up Set buttons
        tbody.querySelectorAll('.logger-set-btn').forEach(btn => {
            btn.addEventListener('click', async () => {
                const name = btn.dataset.name;
                const select = tbody.querySelector(`.logger-level-select[data-name="${name}"]`);
                const level = select.value;
                btn.disabled = true;
                btn.textContent = '...';
                const res = await JoltAPI.loggerUpdate(name, level);
                if (res.ok) {
                    showStatus(`Logger "${name}" changed: ${res.data.previousLevel} → ${res.data.newLevel}`);
                    await loadLoggers();
                } else {
                    showStatus(`Failed to update logger: ${res.data?.error || 'Unknown error'}`, true);
                }
                btn.disabled = false;
                btn.textContent = 'Set';
            });
        });
    }

    function showStatus(msg, isError) {
        const el = document.getElementById('loggerStatus');
        el.textContent = msg;
        el.className = 'status-msg' + (isError ? ' error' : ' success');
        setTimeout(() => { el.textContent = ''; el.className = 'status-msg'; }, 5000);
    }

    function truncate(str, max) {
        return str && str.length > max ? str.substring(0, max) + '...' : str;
    }

    function escapeHtml(text) {
        if (!text) return '';
        const el = document.createElement('span');
        el.textContent = text;
        return el.innerHTML;
    }

    function init() {
        document.getElementById('loggerRefresh')?.addEventListener('click', loadLoggers);
        document.getElementById('loggerSearch')?.addEventListener('input', renderLoggers);
    }

    return { init, loadLoggers };
})();

// Auto-init on DOM ready
document.addEventListener('DOMContentLoaded', () => LoggersPanel.init());
