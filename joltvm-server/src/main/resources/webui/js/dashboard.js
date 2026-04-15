/**
 * JoltVM Web IDE — Dashboard Enhancement Panel
 * Copyright 2026 lucientong. Apache License 2.0
 *
 * Handles the collapsible GC, System Properties, Environment Variables,
 * and Classpath sections on the Dashboard view.
 */
const DashboardPanel = (() => {

    function init() {
        // Load data lazily when sections are expanded
        document.getElementById('gcSection')?.addEventListener('toggle', e => {
            if (e.target.open) loadGcStats();
        });
        document.getElementById('sysPropsSection')?.addEventListener('toggle', e => {
            if (e.target.open) loadSysProps();
        });
        document.getElementById('sysEnvSection')?.addEventListener('toggle', e => {
            if (e.target.open) loadSysEnv();
        });
        document.getElementById('classpathSection')?.addEventListener('toggle', e => {
            if (e.target.open) loadClasspath();
        });

        // Search filters
        document.getElementById('sysPropsSearch')?.addEventListener('input', e => {
            filterTable('sysPropsTable', e.target.value);
        });
        document.getElementById('sysEnvSearch')?.addEventListener('input', e => {
            filterTable('sysEnvTable', e.target.value);
        });
    }

    async function loadGcStats() {
        const el = document.getElementById('gcContent');
        if (!el) return;
        const res = await JoltAPI.jvmGc();
        if (!res.ok) { el.textContent = 'Failed to load GC stats'; return; }

        const d = res.data;
        let html = `<div class="gc-summary">Uptime: ${formatMs(d.uptimeMs)} | Total GC time: ${d.totalGcTimeMs}ms | Overhead: ${d.overheadPercent}%</div>`;
        html += '<table class="data-table"><thead><tr><th>Collector</th><th>Count</th><th>Time (ms)</th><th>Pools</th></tr></thead><tbody>';
        (d.collectors || []).forEach(c => {
            html += `<tr><td>${esc(c.name)}</td><td>${c.collectionCount}</td><td>${c.collectionTimeMs}</td><td>${(c.memoryPools || []).join(', ')}</td></tr>`;
        });
        html += '</tbody></table>';
        el.innerHTML = html;
    }

    async function loadSysProps() {
        const el = document.getElementById('sysPropsContent');
        if (!el) return;
        const res = await JoltAPI.jvmSysProps();
        if (!res.ok) { el.textContent = 'Failed to load'; return; }

        el.innerHTML = buildKvTable('sysPropsTable', res.data.properties || []);
    }

    async function loadSysEnv() {
        const el = document.getElementById('sysEnvContent');
        if (!el) return;
        const res = await JoltAPI.jvmSysEnv();
        if (!res.ok) { el.textContent = 'Failed to load'; return; }

        el.innerHTML = buildKvTable('sysEnvTable', res.data.variables || []);
    }

    async function loadClasspath() {
        const el = document.getElementById('classpathContent');
        if (!el) return;
        const res = await JoltAPI.jvmClasspath();
        if (!res.ok) { el.textContent = 'Failed to load'; return; }

        const d = res.data;
        let html = `<div style="margin-bottom:8px">${d.count} entries</div>`;
        html += '<table class="data-table"><thead><tr><th>Path</th><th>Type</th><th>Size</th><th>Exists</th></tr></thead><tbody>';
        (d.entries || []).forEach(e => {
            const size = e.sizeBytes != null ? formatBytes(e.sizeBytes) : '-';
            const existsIcon = e.exists ? '\u2705' : '\u274c';
            html += `<tr><td title="${esc(e.path)}" style="max-width:500px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(e.path)}</td><td>${e.type}</td><td>${size}</td><td>${existsIcon}</td></tr>`;
        });
        html += '</tbody></table>';

        if (d.modulePath && d.modulePath.length > 0) {
            html += `<h4 style="margin-top:12px">Module Path</h4><ul>`;
            d.modulePath.forEach(p => { html += `<li>${esc(p)}</li>`; });
            html += '</ul>';
        }

        el.innerHTML = html;
    }

    function buildKvTable(tableId, items) {
        let html = `<table class="data-table" id="${tableId}"><thead><tr><th>Key</th><th>Value</th></tr></thead><tbody>`;
        items.forEach(item => {
            const val = item.value === '******'
                ? '<span style="color:#f38ba8">******</span>'
                : esc(item.value);
            html += `<tr data-key="${esc(item.key).toLowerCase()}"><td style="white-space:nowrap">${esc(item.key)}</td><td style="max-width:500px;overflow:hidden;text-overflow:ellipsis;word-break:break-all">${val}</td></tr>`;
        });
        html += '</tbody></table>';
        return html;
    }

    function filterTable(tableId, query) {
        const table = document.getElementById(tableId);
        if (!table) return;
        const q = query.toLowerCase();
        table.querySelectorAll('tbody tr').forEach(row => {
            const key = row.getAttribute('data-key') || '';
            row.style.display = key.includes(q) ? '' : 'none';
        });
    }

    function formatMs(ms) {
        const s = Math.floor(ms / 1000);
        if (s < 60) return s + 's';
        if (s < 3600) return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
        const h = Math.floor(s / 3600);
        return h + 'h ' + Math.floor((s % 3600) / 60) + 'm';
    }

    function formatBytes(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / 1048576).toFixed(1) + ' MB';
    }

    function esc(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    return { init };
})();

document.addEventListener('DOMContentLoaded', () => DashboardPanel.init());
