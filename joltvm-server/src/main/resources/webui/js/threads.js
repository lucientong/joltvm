/**
 * JoltVM Web IDE — Thread Diagnostics Panel
 * Copyright 2026 lucientong. Apache License 2.0
 */
const ThreadsPanel = (() => {
    let autoRefreshTimer = null;

    const STATE_COLORS = {
        RUNNABLE: '#a6e3a1',
        BLOCKED: '#f38ba8',
        WAITING: '#f9e2af',
        TIMED_WAITING: '#fab387',
        NEW: '#cdd6f4',
        TERMINATED: '#6c7086'
    };

    function init() {
        document.getElementById('threadRefresh')?.addEventListener('click', loadThreads);
        document.getElementById('threadTopBtn')?.addEventListener('click', loadTopCpu);
        document.getElementById('threadDeadlockBtn')?.addEventListener('click', checkDeadlocks);
        document.getElementById('threadDumpBtn')?.addEventListener('click', exportDump);
        document.getElementById('threadStateFilter')?.addEventListener('change', loadThreads);
    }

    async function loadThreads() {
        const state = document.getElementById('threadStateFilter')?.value || '';
        const res = await JoltAPI.threadList(state || undefined);
        if (!res.ok) return;

        renderThreadTable(res.data.threads || []);
        renderCounts(res.data.counts);
    }

    async function loadTopCpu() {
        const res = await JoltAPI.threadTop(10, 1000);
        if (!res.ok) return;
        renderThreadTable(res.data.threads || [], true);
    }

    function renderThreadTable(threads, showCpuPercent) {
        const tbody = document.getElementById('threadTableBody');
        if (!tbody) return;
        tbody.innerHTML = '';

        threads.forEach(t => {
            const tr = document.createElement('tr');
            tr.style.cursor = 'pointer';
            tr.addEventListener('click', () => loadThreadDetail(t.id));

            const stateColor = STATE_COLORS[t.state] || '#cdd6f4';
            const cpuCol = showCpuPercent
                ? `<td>${t.cpuPercent != null ? t.cpuPercent + '%' : '-'}</td>`
                : `<td>${t.cpuTimeNanos != null ? (t.cpuTimeNanos / 1e6).toFixed(1) + 'ms' : '-'}</td>`;

            tr.innerHTML = `
                <td>${t.id}</td>
                <td title="${t.name}">${truncate(t.name, 40)}</td>
                <td><span class="state-badge" style="background:${stateColor};color:#1e1e2e">${t.state}</span></td>
                ${cpuCol}
                <td>${t.daemon ? 'Y' : 'N'}</td>
                <td>${t.blockedCount || 0}</td>
                <td>${t.waitedCount || 0}</td>
            `;
            tbody.appendChild(tr);
        });
    }

    function renderCounts(counts) {
        const el = document.getElementById('threadCounts');
        if (!el || !counts) return;
        el.textContent = `Live: ${counts.live} | Daemon: ${counts.daemon} | Peak: ${counts.peak}`;
    }

    async function loadThreadDetail(threadId) {
        const panel = document.getElementById('threadDetailPanel');
        if (!panel) return;

        const res = await JoltAPI.threadDetail(threadId);
        if (!res.ok) {
            panel.innerHTML = '<div class="placeholder-msg">Thread not found</div>';
            return;
        }

        const t = res.data;
        let html = `<div class="thread-detail">`;
        html += `<h4>${escapeHtml(t.name)} #${t.id}</h4>`;
        html += `<div class="thread-meta">`;
        html += `<span class="state-badge" style="background:${STATE_COLORS[t.state] || '#cdd6f4'};color:#1e1e2e">${t.state}</span>`;
        html += ` Daemon: ${t.daemon ? 'Yes' : 'No'}`;
        if (t.lockName) html += ` | Lock: ${escapeHtml(t.lockName)}`;
        if (t.lockOwnerName) html += ` | Owner: ${escapeHtml(t.lockOwnerName)} #${t.lockOwnerId}`;
        html += `</div>`;

        // Stack trace
        if (t.stackTrace && t.stackTrace.length > 0) {
            html += `<pre class="stack-trace">`;
            t.stackTrace.forEach(frame => {
                const loc = frame.fileName
                    ? `(${escapeHtml(frame.fileName)}:${frame.lineNumber})`
                    : frame.nativeMethod ? '(Native Method)' : '(Unknown Source)';
                html += `\tat ${escapeHtml(frame.className)}.${escapeHtml(frame.methodName)}${loc}\n`;
                if (frame.lockedMonitors) {
                    frame.lockedMonitors.forEach(m => {
                        html += `\t- locked &lt;${escapeHtml(m)}&gt;\n`;
                    });
                }
            });
            html += `</pre>`;
        }

        if (t.lockedSynchronizers && t.lockedSynchronizers.length > 0) {
            html += `<div class="locked-syncs">Locked synchronizers: ${t.lockedSynchronizers.map(escapeHtml).join(', ')}</div>`;
        }

        html += `</div>`;
        panel.innerHTML = html;
    }

    async function checkDeadlocks() {
        const res = await JoltAPI.threadDeadlocks();
        if (!res.ok) return;

        if (!res.data.deadlocked) {
            showToast('No deadlocks detected', 'info');
            return;
        }

        showToast(`Found ${res.data.count} deadlocked thread(s)!`, 'error');
        renderThreadTable(res.data.threads || []);
    }

    async function exportDump() {
        const res = await JoltAPI.threadDump();
        if (!res.ok) return;

        const text = typeof res.data === 'string' ? res.data : JSON.stringify(res.data);
        const blob = new Blob([text], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `thread-dump-${new Date().toISOString().replace(/[:.]/g, '-')}.txt`;
        a.click();
        URL.revokeObjectURL(url);
        showToast('Thread dump exported', 'info');
    }

    function truncate(str, max) {
        return str && str.length > max ? str.substring(0, max) + '...' : str;
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function showToast(msg, type) {
        // Delegate to app.js showToast if available
        if (typeof window.showToast === 'function') {
            window.showToast(msg, type);
        }
    }

    return { init, loadThreads };
})();

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => ThreadsPanel.init());
