/**
 * JoltVM Web IDE — Watch Panel
 * Copyright 2026 lucientong. Apache License 2.0
 */
const WatchPanel = (() => {
    'use strict';

    const PRESETS = [
        { label: 'Slow methods (>1ms)', classPattern: '', methodPattern: '*', conditionExpr: '#cost > 1000000', durationMs: 120000 },
        { label: 'All exceptions', classPattern: '', methodPattern: '*', conditionExpr: '#throwExp != null', durationMs: 60000 },
    ];

    async function loadWatches() {
        const container = document.getElementById('watchListContent');
        container.innerHTML = '<div class="placeholder-msg">Loading watches...</div>';
        const res = await JoltAPI.watchList();
        if (!res.ok) {
            container.innerHTML = '<div class="error-msg">Failed to load watches</div>';
            return;
        }
        renderWatchList(res.data);
    }

    function renderWatchList(data) {
        const container = document.getElementById('watchListContent');
        document.getElementById('watchCount').textContent = `${data.count} / ${data.maxConcurrent} watches`;

        if (data.watches.length === 0) {
            container.innerHTML = '<div class="placeholder-msg">No active watches. Click "New Watch" to start.</div>';
            return;
        }

        let html = '<table class="data-table"><thead><tr><th>ID</th><th>Class</th><th>Method</th><th>Condition</th><th>Records</th><th>Status</th><th>Actions</th></tr></thead><tbody>';
        for (const w of data.watches) {
            const status = w.active ? '<span class="badge badge-green">Active</span>'
                : w.expired ? '<span class="badge badge-yellow">Expired</span>'
                : '<span class="badge badge-red">Stopped</span>';
            const cond = w.conditionExpr ? esc(truncate(w.conditionExpr, 28)) : '-';
            html += `<tr>
                <td><code>${esc(w.id)}</code></td>
                <td title="${esc(w.classPattern)}">${esc(truncate(w.classPattern, 40))}</td>
                <td>${esc(w.methodPattern)}</td>
                <td title="${esc(w.conditionExpr || '')}">${cond}</td>
                <td>${w.recordCount} / ${w.maxRecords}${w.totalSeen != null ? ` <small>(seen ${w.totalSeen})</small>` : ''}</td>
                <td>${status}</td>
                <td>
                    <button class="btn btn-sm watch-records-btn" data-id="${w.id}">Records</button>
                    ${w.active ? `<button class="btn btn-sm btn-warning watch-stop-btn" data-id="${w.id}">Stop</button>` : ''}
                    <button class="btn btn-sm btn-danger watch-delete-btn" data-id="${w.id}">Delete</button>
                </td>
            </tr>`;
        }
        html += '</tbody></table>';
        container.innerHTML = html;

        container.querySelectorAll('.watch-records-btn').forEach(btn => {
            btn.addEventListener('click', () => loadRecords(btn.dataset.id));
        });
        container.querySelectorAll('.watch-stop-btn').forEach(btn => {
            btn.addEventListener('click', async () => { await JoltAPI.watchStop(btn.dataset.id); loadWatches(); });
        });
        container.querySelectorAll('.watch-delete-btn').forEach(btn => {
            btn.addEventListener('click', async () => { await JoltAPI.watchDelete(btn.dataset.id); loadWatches(); });
        });
    }

    async function loadRecords(id) {
        const panel = document.getElementById('watchRecordsPanel');
        panel.innerHTML = '<div class="placeholder-msg">Loading records...</div>';
        const res = await JoltAPI.watchRecords(id);
        if (!res.ok) {
            panel.innerHTML = '<div class="error-msg">Failed to load records</div>';
            return;
        }
        const data = res.data;
        let html = `<h4>Watch ${esc(id)} — ${data.count} records</h4>`;
        if (data.records.length === 0) {
            html += '<div class="placeholder-msg">No records yet</div>';
        } else {
            html += '<table class="data-table"><thead><tr><th>Time</th><th>Method</th><th>Duration</th><th>Args</th><th>Return</th><th>Exception</th></tr></thead><tbody>';
            for (const r of data.records) {
                const dur = r.durationMs != null ? r.durationMs.toFixed(2) + 'ms' : '-';
                const exc = r.exceptionType ? `<span class="error-text">${esc(r.exceptionType)}</span>` : '-';
                html += `<tr>
                    <td>${esc(r.timestamp?.substring(11, 23) || '')}</td>
                    <td>${esc(r.className?.split('.').pop() || '')}.${esc(r.methodName)}</td>
                    <td>${dur}</td>
                    <td title="${esc(JSON.stringify(r.args))}">${esc(truncate(JSON.stringify(r.args), 40))}</td>
                    <td title="${esc(r.returnValue || '')}">${esc(truncate(r.returnValue || '-', 30))}</td>
                    <td>${exc}</td>
                </tr>`;
            }
            html += '</tbody></table>';
        }
        panel.innerHTML = html;
    }

    async function startWatch() {
        const classPattern = document.getElementById('watchClassPattern').value.trim();
        const methodPattern = document.getElementById('watchMethodPattern').value.trim() || '*';
        const conditionExpr = document.getElementById('watchConditionExpr')?.value.trim() || null;
        const durationMs = parseInt(document.getElementById('watchDuration').value) * 1000 || 60000;

        if (!classPattern) {
            alert('Class pattern is required');
            return;
        }

        const res = await JoltAPI.watchStart(classPattern, methodPattern, conditionExpr || null, 1000, durationMs);
        if (res.ok) {
            loadWatches();
        } else {
            alert('Failed: ' + (res.data?.error || res.data));
        }
    }

    function applyPreset(preset) {
        if (preset.classPattern) {
            document.getElementById('watchClassPattern').value = preset.classPattern;
        }
        document.getElementById('watchMethodPattern').value = preset.methodPattern || '*';
        const condEl = document.getElementById('watchConditionExpr');
        if (condEl) condEl.value = preset.conditionExpr || '';
        if (preset.durationMs) {
            document.getElementById('watchDuration').value = Math.round(preset.durationMs / 1000);
        }
    }

    function esc(text) {
        if (!text) return '';
        const el = document.createElement('span');
        el.textContent = text;
        return el.innerHTML;
    }

    function truncate(str, max) {
        return str && str.length > max ? str.substring(0, max) + '...' : str;
    }

    function init() {
        document.getElementById('watchStartBtn')?.addEventListener('click', startWatch);
        document.getElementById('watchRefresh')?.addEventListener('click', loadWatches);

        const toolbar = document.querySelector('#view-watch .toolbar');
        if (toolbar && !document.getElementById('watchPresets')) {
            const wrap = document.createElement('span');
            wrap.id = 'watchPresets';
            wrap.style.marginLeft = '8px';
            for (const p of PRESETS) {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'btn btn-sm';
                btn.textContent = p.label;
                btn.title = p.conditionExpr || '';
                btn.addEventListener('click', () => applyPreset(p));
                wrap.appendChild(btn);
            }
            toolbar.appendChild(wrap);
        }
    }

    return { init, loadWatches };
})();

// Auto-init on DOM ready
document.addEventListener('DOMContentLoaded', () => WatchPanel.init());
