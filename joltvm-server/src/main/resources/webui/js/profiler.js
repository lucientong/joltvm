/**
 * JoltVM Web IDE — async-profiler Panel
 * Copyright 2026 lucientong. Apache License 2.0
 */
const ProfilerPanel = (() => {
    'use strict';

    let lastSessionId = null;

    async function checkStatus() {
        const el = document.getElementById('asyncProfilerStatus');
        if (!el) return;
        const res = await JoltAPI.asyncProfilerStatus();
        if (!res.ok) {
            el.innerHTML = '<span class="error-text">Failed to check status</span>';
            return;
        }
        const d = res.data;
        if (d.available) {
            el.innerHTML = `<span class="badge badge-green">Available</span> ${esc(d.platform)} | ${esc(d.nativeLibPath || '')}`;
            document.getElementById('asyncStartBtn')?.removeAttribute('disabled');
        } else {
            el.innerHTML = `<span class="badge badge-yellow">Unavailable</span> ${esc(d.unavailableReason || '')}`;
            document.getElementById('asyncStartBtn')?.setAttribute('disabled', 'true');
        }
        if (d.profiling) {
            el.innerHTML += ' | <span class="badge badge-blue">Profiling...</span>';
        }
    }

    async function startProfiling() {
        const event = document.getElementById('asyncEvent')?.value || 'cpu';
        const duration = parseInt(document.getElementById('asyncDuration')?.value) || 30;

        const res = await JoltAPI.asyncProfilerStart(event, duration, 0);
        if (res.ok) {
            lastSessionId = res.data.sessionId;
            showMsg(`Profiling started (${event}, ${duration}s) — session: ${lastSessionId}`, false);
        } else {
            showMsg(res.data?.error || 'Failed to start', true);
        }
        checkStatus();
    }

    async function stopProfiling() {
        const res = await JoltAPI.asyncProfilerStop();
        if (res.ok) {
            lastSessionId = res.data.sessionId;
            showMsg(`Profiling stopped — ${res.data.sampleCount} samples`, false);
        } else {
            showMsg(res.data?.error || 'Failed to stop', true);
        }
        checkStatus();
    }

    async function loadFlameGraph() {
        if (!lastSessionId) {
            showMsg('No profiler session. Start and stop a profiling session first.', true);
            return;
        }
        const res = await JoltAPI.asyncProfilerFlameGraph(lastSessionId);
        if (!res.ok) {
            showMsg('Failed to load flame graph', true);
            return;
        }
        if (res.data.data && typeof flamegraph !== 'undefined') {
            const container = document.getElementById('asyncFlameContainer');
            if (container) {
                container.innerHTML = '';
                const chart = flamegraph().width(container.clientWidth || 960);
                d3.select(container).datum(res.data.data).call(chart);
            }
        } else {
            showMsg('Flame graph data: ' + JSON.stringify(res.data.status), false);
        }
    }

    function showMsg(msg, isError) {
        const el = document.getElementById('asyncProfilerMsg');
        if (el) {
            el.textContent = msg;
            el.className = 'status-msg ' + (isError ? 'error' : 'success');
            setTimeout(() => { el.textContent = ''; el.className = 'status-msg'; }, 8000);
        }
    }

    function esc(text) {
        if (!text) return '';
        const el = document.createElement('span');
        el.textContent = text;
        return el.innerHTML;
    }

    function init() {
        document.getElementById('asyncStartBtn')?.addEventListener('click', startProfiling);
        document.getElementById('asyncStopBtn')?.addEventListener('click', stopProfiling);
        document.getElementById('asyncFlameBtn')?.addEventListener('click', loadFlameGraph);
        document.getElementById('asyncCheckBtn')?.addEventListener('click', checkStatus);
    }

    return { init, checkStatus };
})();

// Auto-init on DOM ready
document.addEventListener('DOMContentLoaded', () => ProfilerPanel.init());
