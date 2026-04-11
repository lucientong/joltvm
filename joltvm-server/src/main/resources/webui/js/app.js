/**
 * JoltVM Web IDE — Main Application
 * Copyright 2026 lucientong. Apache License 2.0
 */
(() => {
    'use strict';

    // ================================================================
    // Toast Notifications
    // ================================================================
    const toastContainer = document.createElement('div');
    toastContainer.className = 'toast-container';
    document.body.appendChild(toastContainer);

    function toast(msg, type) {
        const el = document.createElement('div');
        el.className = 'toast ' + (type || 'info');
        el.textContent = msg;
        toastContainer.appendChild(el);
        setTimeout(() => {
            el.classList.add('exiting');
            el.addEventListener('animationend', () => el.remove(), { once: true });
        }, 3500);
    }

    // ================================================================
    // Navigation
    // ================================================================
    const navTabs = document.getElementById('navTabs');
    navTabs.addEventListener('click', (e) => {
        if (!e.target.classList.contains('nav-tab')) return;
        document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        e.target.classList.add('active');
        const viewId = 'view-' + e.target.dataset.view;
        document.getElementById(viewId).classList.add('active');
        onViewActivated(e.target.dataset.view);
    });

    function onViewActivated(view) {
        if (view === 'dashboard') loadDashboard();
        if (view === 'classes') loadClasses();
        if (view === 'trace') loadTraceStatus();
        if (view === 'spring') loadSpring();
        if (view === 'audit') loadAudit();
    }

    // ================================================================
    // Dashboard
    // ================================================================
    async function loadDashboard() {
        const res = await JoltAPI.health();
        const status = document.getElementById('statusIndicator');
        const statusText = document.getElementById('statusText');

        if (!res.ok) {
            status.className = 'status-indicator error';
            statusText.textContent = 'Disconnected';
            document.getElementById('healthStatus').textContent = 'DOWN';
            document.getElementById('healthStatus').className = 'health-status down';
            return;
        }

        const d = res.data;
        status.className = 'status-indicator connected';
        statusText.textContent = 'Connected';

        // Version
        document.getElementById('versionBadge').textContent = 'v' + (d.version || 'dev');

        // Health
        const hs = document.getElementById('healthStatus');
        hs.textContent = d.status || 'UP';
        hs.className = 'health-status ' + ((d.status || '').toLowerCase() === 'up' ? 'up' : 'down');

        // Memory
        if (d.memory) {
            const used = d.memory.totalMb - d.memory.freeMb;
            const max = d.memory.maxMb;
            const pct = Math.round((used / max) * 100);
            const cls = pct > 90 ? 'critical' : pct > 70 ? 'high' : 'used';
            document.getElementById('memoryBars').innerHTML = `
                <div class="memory-bar-item">
                    <div class="memory-bar-label"><span>Heap Used</span><span>${used} MB / ${max} MB (${pct}%)</span></div>
                    <div class="memory-bar-track"><div class="memory-bar-fill ${cls}" style="width:${pct}%"></div></div>
                </div>
                <div class="memory-bar-item">
                    <div class="memory-bar-label"><span>Total Allocated</span><span>${d.memory.totalMb} MB</span></div>
                    <div class="memory-bar-track"><div class="memory-bar-fill used" style="width:${Math.round(d.memory.totalMb/max*100)}%"></div></div>
                </div>`;
        }

        // JVM Info
        if (d.jvm) {
            document.getElementById('jvmInfo').innerHTML = [
                ['VM Name', d.jvm.name],
                ['VM Version', d.jvm.version],
                ['Vendor', d.jvm.vendor],
                ['PID', d.jvm.pid],
                ['Uptime', formatUptime(d.jvm.uptimeMs)]
            ].map(([l, v]) => `<div class="info-row"><span class="label">${l}</span><span class="value">${v || '-'}</span></div>`).join('');
        }

        // Quick stats — derive from health response
        const port = d.port || 7758;
        const endpoints = d.routeCount || 18;
        document.getElementById('quickStats').innerHTML = `
            <div class="info-row"><span class="label">Server Port</span><span class="value">${port}</span></div>
            <div class="info-row"><span class="label">API Endpoints</span><span class="value">${endpoints}</span></div>
            <div class="info-row"><span class="label">Status</span><span class="value" style="color:var(--success)">Running</span></div>`;
    }

    function formatUptime(ms) {
        if (!ms) return '-';
        const s = Math.floor(ms / 1000);
        const m = Math.floor(s / 60);
        const h = Math.floor(m / 60);
        const d = Math.floor(h / 24);
        if (d > 0) return d + 'd ' + (h % 24) + 'h';
        if (h > 0) return h + 'h ' + (m % 60) + 'm';
        if (m > 0) return m + 'm ' + (s % 60) + 's';
        return s + 's';
    }

    // ================================================================
    // Classes
    // ================================================================
    let classPage = 1;
    const classSize = 50;

    document.getElementById('classRefresh').addEventListener('click', () => { classPage = 1; loadClasses(); });
    document.getElementById('classSearch').addEventListener('input', debounce(() => { classPage = 1; loadClasses(); }, 300));
    document.getElementById('packageFilter').addEventListener('input', debounce(() => { classPage = 1; loadClasses(); }, 300));

    async function loadClasses() {
        const search = document.getElementById('classSearch').value;
        const pkg = document.getElementById('packageFilter').value;
        const res = await JoltAPI.listClasses(classPage, classSize, pkg, search);
        if (!res.ok) { toast('Failed to load classes', 'error'); return; }
        const d = res.data;
        const list = document.getElementById('classList');
        if (!d.classes || d.classes.length === 0) {
            list.innerHTML = '<div class="placeholder-msg">No classes found</div>';
        } else {
            list.innerHTML = d.classes.map(c =>
                `<div class="class-item" data-class="${esc(c.name)}" title="${esc(c.name)}">${esc(c.name)}</div>`
            ).join('');
            list.querySelectorAll('.class-item').forEach(el => {
                el.addEventListener('click', () => selectClass(el.dataset.class));
            });
        }
        renderPagination(d.page, d.totalPages);
    }

    function renderPagination(current, total) {
        const el = document.getElementById('classPagination');
        if (!total || total <= 1) { el.innerHTML = ''; return; }
        let html = '';
        if (current > 1) html += `<button class="btn" data-page="${current-1}">‹</button>`;
        const start = Math.max(1, current - 2);
        const end = Math.min(total, current + 2);
        for (let i = start; i <= end; i++) {
            html += `<button class="btn ${i === current ? 'current' : ''}" data-page="${i}">${i}</button>`;
        }
        if (current < total) html += `<button class="btn" data-page="${current+1}">›</button>`;
        el.innerHTML = html;
        el.querySelectorAll('.btn').forEach(btn => {
            btn.addEventListener('click', () => { classPage = parseInt(btn.dataset.page); loadClasses(); });
        });
    }

    async function selectClass(className) {
        document.querySelectorAll('.class-item').forEach(el => el.classList.remove('selected'));
        const sel = document.querySelector(`.class-item[data-class="${CSS.escape(className)}"]`);
        if (sel) sel.classList.add('selected');

        const panel = document.getElementById('classDetailPanel');
        panel.innerHTML = '<div class="placeholder-msg">Loading...</div>';

        const res = await JoltAPI.classDetail(className);
        if (!res.ok) { panel.innerHTML = '<div class="placeholder-msg">Failed to load class detail</div>'; return; }
        const d = res.data;

        let html = `<div class="detail-section"><h4>${esc(d.name || className)}</h4>`;
        if (d.modifiers) html += `<div class="detail-item"><span class="modifier">${esc(d.modifiers)}</span></div>`;
        if (d.superclass) html += `<div class="detail-item">extends <span class="type">${esc(d.superclass)}</span></div>`;
        if (d.interfaces && d.interfaces.length) html += `<div class="detail-item">implements ${d.interfaces.map(i => `<span class="type">${esc(i)}</span>`).join(', ')}</div>`;
        html += '</div>';

        if (d.fields && d.fields.length) {
            html += '<div class="detail-section"><h4>Fields (' + d.fields.length + ')</h4>';
            d.fields.forEach(f => { html += `<div class="detail-item"><span class="modifier">${esc(f.modifiers || '')}</span> <span class="type">${esc(f.type || '')}</span> <span class="name">${esc(f.name)}</span></div>`; });
            html += '</div>';
        }

        if (d.methods && d.methods.length) {
            html += '<div class="detail-section"><h4>Methods (' + d.methods.length + ')</h4>';
            d.methods.forEach(m => { html += `<div class="detail-item"><span class="modifier">${esc(m.modifiers || '')}</span> <span class="type">${esc(m.returnType || 'void')}</span> <span class="name">${esc(m.name)}</span>(${(m.parameterTypes || []).map(p => `<span class="type">${esc(p)}</span>`).join(', ')})</div>`; });
            html += '</div>';
        }

        panel.innerHTML = html;
    }

    // ================================================================
    // Hot-Swap Editor
    // ================================================================
    let monacoEditor = null;
    let currentEditClass = '';

    document.getElementById('loadSourceBtn').addEventListener('click', loadSource);
    document.getElementById('compileBtn').addEventListener('click', doCompile);
    document.getElementById('hotswapBtn').addEventListener('click', doHotSwap);
    document.getElementById('rollbackBtn').addEventListener('click', doRollback);

    async function loadSource() {
        const className = document.getElementById('hotswapClassName').value.trim();
        if (!className) { toast('Enter a class name first', 'error'); return; }
        currentEditClass = className;

        setEditorStatus('Loading source for ' + className + '...');
        const res = await JoltAPI.classSource(className);
        if (!res.ok) {
            setEditorStatus('Failed to load source: ' + errMsg(res.data), true);
            return;
        }

        const source = res.data.source || res.data;
        const container = document.getElementById('monacoContainer');

        // Use Monaco if available, otherwise use a textarea
        if (window.monaco) {
            if (!monacoEditor) {
                container.innerHTML = '';
                monacoEditor = monaco.editor.create(container, {
                    value: source,
                    language: 'java',
                    theme: 'vs-dark',
                    automaticLayout: true,
                    minimap: { enabled: true },
                    fontSize: 13,
                    fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace"
                });
            } else {
                monacoEditor.setValue(source);
            }
        } else {
            container.innerHTML = `<textarea id="sourceEditor" style="width:100%;height:100%;background:var(--bg-primary);color:var(--text-primary);border:none;padding:12px;font-family:var(--font-mono);font-size:13px;resize:none;outline:none">${esc(source)}</textarea>`;
        }

        document.getElementById('compileBtn').disabled = false;
        document.getElementById('hotswapBtn').disabled = false;
        document.getElementById('rollbackBtn').disabled = false;
        setEditorStatus('Source loaded: ' + className);
    }

    async function doCompile() {
        if (!currentEditClass) return;
        const source = getEditorContent();
        if (!source) return;

        setEditorStatus('Compiling ' + currentEditClass + '...');
        const res = await JoltAPI.compile(currentEditClass, source);
        if (res.ok) {
            toast('Compilation successful: ' + currentEditClass, 'success');
            setEditorStatus('Compilation successful — no errors found');
        } else {
            toast('Compilation failed: ' + errMsg(res.data), 'error');
            setEditorStatus('Compilation failed: ' + errMsg(res.data), true);
        }
    }

    async function doHotSwap() {
        if (!currentEditClass) return;
        const source = getEditorContent();
        if (!source) return;

        setEditorStatus('Hot-swapping ' + currentEditClass + '...');
        const res = await JoltAPI.hotswap(currentEditClass, source);
        if (res.ok) {
            toast('Hot-swap successful: ' + currentEditClass, 'success');
            setEditorStatus('Hot-swap successful!');
        } else {
            toast('Hot-swap failed: ' + errMsg(res.data), 'error');
            setEditorStatus('Hot-swap failed: ' + errMsg(res.data), true);
        }
    }

    async function doRollback() {
        if (!currentEditClass) return;
        setEditorStatus('Rolling back ' + currentEditClass + '...');
        const res = await JoltAPI.rollback(currentEditClass);
        if (res.ok) {
            toast('Rollback successful: ' + currentEditClass, 'success');
            setEditorStatus('Rollback successful!');
        } else {
            toast('Rollback failed: ' + errMsg(res.data), 'error');
            setEditorStatus('Rollback failed: ' + errMsg(res.data), true);
        }
    }

    function getEditorContent() {
        if (monacoEditor) return monacoEditor.getValue();
        const ta = document.getElementById('sourceEditor');
        return ta ? ta.value : '';
    }

    function setEditorStatus(msg, isError) {
        const el = document.getElementById('editorStatus');
        el.textContent = msg;
        el.className = 'editor-status' + (isError ? ' error' : ' success');
    }

    // ================================================================
    // Flame Graph / Trace
    // ================================================================
    let flameChart = null;

    document.getElementById('startTraceBtn').addEventListener('click', startTrace);
    document.getElementById('stopTraceBtn').addEventListener('click', stopTrace);
    document.getElementById('refreshFlameBtn').addEventListener('click', refreshFlameGraph);

    async function startTrace() {
        const type = document.getElementById('traceType').value;
        const body = { type, duration: parseInt(document.getElementById('traceDuration').value) || 30 };
        if (type === 'trace') {
            body.className = document.getElementById('traceClassName').value.trim();
            body.methodName = document.getElementById('traceMethodName').value.trim() || null;
            if (!body.className) { toast('Enter a class name for trace', 'error'); return; }
        }
        if (type === 'sample') {
            body.interval = 10;
        }

        const res = await JoltAPI.traceStart(body);
        if (res.ok) {
            toast('Trace started', 'success');
            document.getElementById('startTraceBtn').disabled = true;
            document.getElementById('stopTraceBtn').disabled = false;
        } else {
            toast('Failed to start trace: ' + errMsg(res.data), 'error');
        }
        loadTraceStatus();
    }

    async function stopTrace() {
        const res = await JoltAPI.traceStop();
        if (res.ok) {
            toast('Trace stopped', 'success');
            document.getElementById('startTraceBtn').disabled = false;
            document.getElementById('stopTraceBtn').disabled = true;
        } else {
            toast('Failed to stop trace: ' + errMsg(res.data), 'error');
        }
        loadTraceStatus();
        setTimeout(refreshFlameGraph, 500);
    }

    async function loadTraceStatus() {
        const res = await JoltAPI.traceStatus();
        const el = document.getElementById('traceStatus');
        if (!res.ok) { el.className = 'trace-status'; return; }
        const d = res.data;
        const active = d.tracing || d.sampling;
        el.className = 'trace-status' + (active ? ' active' : '');
        let info = [];
        if (d.tracing) info.push('Tracing: active');
        if (d.sampling) info.push('Sampling: active');
        if (d.recordCount != null) info.push('Records: ' + d.recordCount);
        if (d.sampleCount != null) info.push('Samples: ' + d.sampleCount);
        el.textContent = info.length ? info.join(' | ') : 'Idle';

        document.getElementById('startTraceBtn').disabled = active;
        document.getElementById('stopTraceBtn').disabled = !active;
    }

    async function refreshFlameGraph() {
        const container = document.getElementById('flamegraphContainer');

        // CDN offline graceful degradation
        if (typeof d3 === 'undefined' || typeof flamegraph === 'undefined') {
            container.innerHTML = '<div class="placeholder-msg">' +
                '<strong>Flame graph unavailable</strong><br>' +
                '<span style="font-size:12px;color:var(--text-muted)">d3 / d3-flame-graph CDN failed to load. Check network connectivity and refresh the page.</span></div>';
            return;
        }

        const res = await JoltAPI.traceFlameGraph();
        if (!res.ok || !res.data || (!res.data.children && !res.data.name)) {
            container.innerHTML = '<div class="placeholder-msg">No flame graph data available. Start a trace first.</div>';
            return;
        }

        container.innerHTML = '';
        try {
            const width = container.clientWidth || 800;
            flameChart = flamegraph()
                .width(width)
                .cellHeight(20)
                .transitionDuration(300)
                .sort(true)
                .title('');

            d3.select(container)
                .datum(res.data)
                .call(flameChart);
        } catch (e) {
            container.innerHTML = '<div class="placeholder-msg">Error rendering flame graph: ' + esc(e.message) + '</div>';
        }

        // Also load records
        const recRes = await JoltAPI.traceRecords(20);
        const recEl = document.getElementById('traceRecords');
        if (recRes.ok && recRes.data && recRes.data.records && recRes.data.records.length) {
            recEl.innerHTML = '<table class="mapping-table"><thead><tr><th>Class</th><th>Method</th><th>Duration</th><th>Thread</th></tr></thead><tbody>' +
                recRes.data.records.map(r => `<tr><td>${esc(r.className || '')}</td><td>${esc(r.methodName || '')}</td><td>${r.durationMs != null ? r.durationMs + 'ms' : (r.durationNanos != null ? (r.durationNanos/1000000).toFixed(2) + 'ms' : '-')}</td><td>${esc(r.threadName || '')}</td></tr>`).join('') +
                '</tbody></table>';
        } else {
            recEl.innerHTML = '';
        }
    }

    // ================================================================
    // Spring
    // ================================================================
    document.getElementById('springRefresh').addEventListener('click', loadSpring);
    document.getElementById('springView').addEventListener('change', loadSpring);
    document.getElementById('springSearch').addEventListener('input', debounce(loadSpring, 300));
    document.getElementById('stereotypeFilter').addEventListener('change', loadSpring);

    async function loadSpring() {
        const view = document.getElementById('springView').value;
        const search = document.getElementById('springSearch').value;
        const stereotype = document.getElementById('stereotypeFilter').value;
        const content = document.getElementById('springContent');

        if (view === 'beans') {
            const res = await JoltAPI.springBeans(1, 100, '', search, stereotype);
            if (!res.ok) {
                if (res.status === 503) {
                    content.innerHTML = '<div class="placeholder-msg">Spring context not detected. Attach to a Spring Boot application first.</div>';
                } else {
                    content.innerHTML = '<div class="placeholder-msg">Failed to load beans</div>';
                }
                return;
            }
            const d = res.data;
            if (!d.beans || d.beans.length === 0) {
                content.innerHTML = '<div class="placeholder-msg">No beans found</div>';
                return;
            }
            content.innerHTML = '<table class="bean-table"><thead><tr><th>Bean Name</th><th>Class</th><th>Stereotype</th><th>Scope</th></tr></thead><tbody>' +
                d.beans.map(b => `<tr style="cursor:pointer" data-bean="${esc(b.name || b.beanName)}">
                    <td>${esc(b.name || b.beanName)}</td>
                    <td>${esc(b.className || b.type || '')}</td>
                    <td>${b.stereotype ? `<span class="stereotype-badge ${(b.stereotype||'').toLowerCase()}">${esc(b.stereotype)}</span>` : '-'}</td>
                    <td>${esc(b.scope || 'singleton')}</td></tr>`).join('') +
                '</tbody></table>';
            // Bind click on bean rows → show bean detail + dependency chain
            content.querySelectorAll('tr[data-bean]').forEach(row => {
                row.addEventListener('click', () => showBeanDetail(row.dataset.bean));
            });
        } else if (view === 'mappings') {
            const res = await JoltAPI.springMappings('', search);
            if (!res.ok) {
                content.innerHTML = '<div class="placeholder-msg">' + (res.status === 503 ? 'Spring context not detected.' : 'Failed to load mappings') + '</div>';
                return;
            }
            const d = res.data;
            const mappings = d.mappings || d;
            if (!Array.isArray(mappings) || mappings.length === 0) {
                content.innerHTML = '<div class="placeholder-msg">No request mappings found</div>';
                return;
            }
            content.innerHTML = '<table class="mapping-table"><thead><tr><th>Method</th><th>URL</th><th>Handler</th></tr></thead><tbody>' +
                mappings.map(m => `<tr>
                    <td><span class="method-badge ${(m.method || m.httpMethod || 'GET').toLowerCase()}">${esc(m.method || m.httpMethod || 'GET')}</span></td>
                    <td style="font-family:var(--font-mono)">${esc(m.url || m.pattern || '')}</td>
                    <td>${esc(m.handler || m.handlerMethod || '')}</td></tr>`).join('') +
                '</tbody></table>';
        } else if (view === 'dependencies') {
            const res = await JoltAPI.springDependencies();
            if (!res.ok) {
                content.innerHTML = '<div class="placeholder-msg">' + (res.status === 503 ? 'Spring context not detected.' : 'Failed to load dependencies') + '</div>';
                return;
            }
            const d = res.data;
            const nodes = d.nodes || d.graph || d;
            if (!Array.isArray(nodes) || nodes.length === 0) {
                content.innerHTML = '<div class="placeholder-msg">No dependency graph data</div>';
                return;
            }
            let html = '<div class="dep-graph">';
            nodes.forEach(n => {
                html += `<div class="dep-node"><span class="dep-name">${esc(n.name || n.beanName)}</span> <span class="dep-type">[${esc(n.stereotype || n.type || '')}]</span>`;
                if (n.dependencies && n.dependencies.length) {
                    html += '<div style="padding-left:24px">';
                    n.dependencies.forEach(dep => {
                        const depName = typeof dep === 'string' ? dep : (dep.name || dep.beanName || '');
                        html += `<div class="dep-node">→ <span class="dep-name">${esc(depName)}</span></div>`;
                    });
                    html += '</div>';
                }
                html += '</div>';
            });
            html += '</div>';
            content.innerHTML = html;
        }
    }

    // ================================================================
    // Spring Bean Detail (click-through)
    // ================================================================
    async function showBeanDetail(beanName) {
        const content = document.getElementById('springContent');
        content.innerHTML = '<div class="placeholder-msg">Loading bean detail...</div>';

        const [detailRes, depRes] = await Promise.all([
            JoltAPI.springBeanDetail(beanName),
            JoltAPI.springDependencyChain(beanName)
        ]);

        let html = '<div style="margin-bottom:12px"><button class="btn" id="backToBeans">← Back to Bean List</button></div>';

        // Bean detail section
        if (detailRes.ok && detailRes.data) {
            const b = detailRes.data;
            html += '<div class="card" style="margin-bottom:12px">';
            html += `<h3 style="text-transform:none;letter-spacing:0">Bean: ${esc(b.name || b.beanName || beanName)}</h3>`;
            html += '<div style="display:grid;grid-template-columns:120px 1fr;gap:4px 12px;font-size:13px">';
            html += `<span class="label">Class</span><span class="value" style="font-family:var(--font-mono);font-size:12px">${esc(b.className || b.type || '-')}</span>`;
            if (b.stereotype) html += `<span class="label">Stereotype</span><span><span class="stereotype-badge ${(b.stereotype||'').toLowerCase()}">${esc(b.stereotype)}</span></span>`;
            html += `<span class="label">Scope</span><span class="value">${esc(b.scope || 'singleton')}</span>`;
            if (b.primary != null) html += `<span class="label">Primary</span><span class="value">${b.primary ? 'Yes' : 'No'}</span>`;
            if (b.lazy != null) html += `<span class="label">Lazy</span><span class="value">${b.lazy ? 'Yes' : 'No'}</span>`;
            html += '</div>';
            // Dependencies injected into this bean
            if (b.dependencies && b.dependencies.length) {
                html += `<div style="margin-top:12px"><h4 style="font-size:13px;color:var(--accent);margin-bottom:6px">Dependencies (${b.dependencies.length})</h4>`;
                b.dependencies.forEach(dep => {
                    const depName = typeof dep === 'string' ? dep : (dep.name || dep.beanName || '');
                    html += `<div class="detail-item" style="cursor:pointer;padding:3px 0" data-dep-bean="${esc(depName)}">→ <span class="dep-name">${esc(depName)}</span></div>`;
                });
                html += '</div>';
            }
            html += '</div>';
        } else {
            html += '<div class="card" style="margin-bottom:12px"><div class="placeholder-msg">Could not load bean detail</div></div>';
        }

        // Dependency chain section
        if (depRes.ok && depRes.data) {
            const chain = depRes.data.chain || depRes.data.dependencies || depRes.data;
            if (Array.isArray(chain) && chain.length > 0) {
                html += '<div class="card"><h3 style="text-transform:none;letter-spacing:0">Dependency Chain</h3>';
                html += '<div class="dep-graph">';
                chain.forEach(n => {
                    const name = typeof n === 'string' ? n : (n.name || n.beanName || '');
                    const type = (typeof n === 'object' && n) ? (n.stereotype || n.type || '') : '';
                    html += `<div class="dep-node"><span class="dep-name">${esc(name)}</span>`;
                    if (type) html += ` <span class="dep-type">[${esc(type)}]</span>`;
                    html += '</div>';
                });
                html += '</div></div>';
            }
        }

        content.innerHTML = html;

        // Back button
        const backBtn = document.getElementById('backToBeans');
        if (backBtn) backBtn.addEventListener('click', loadSpring);

        // Dependency links — click to drill down
        content.querySelectorAll('[data-dep-bean]').forEach(el => {
            el.addEventListener('click', () => showBeanDetail(el.dataset.depBean));
        });
    }

    // ================================================================
    // Audit Log
    // ================================================================
    document.getElementById('auditRefresh').addEventListener('click', loadAudit);

    async function loadAudit() {
        const res = await JoltAPI.hotswapHistory();
        const el = document.getElementById('auditList');
        if (!res.ok) { el.innerHTML = '<div class="placeholder-msg">Failed to load audit log</div>'; return; }
        const d = res.data;
        const records = d.history || d.records || d;
        if (!Array.isArray(records) || records.length === 0) {
            el.innerHTML = '<div class="placeholder-msg">No hot-swap operations recorded yet</div>';
            return;
        }
        el.innerHTML = records.map(r => `
            <div class="audit-item">
                <div class="audit-header">
                    <span class="audit-action ${(r.action||'').toLowerCase()}">${esc(r.action || 'UNKNOWN')}</span>
                    <span class="audit-status ${(r.status||'').toLowerCase()}">${esc(r.status || '')}</span>
                </div>
                <div class="audit-class">${esc(r.className || '')}</div>
                <div style="display:flex;justify-content:space-between;margin-top:4px">
                    <span class="audit-time">${r.timestamp ? new Date(r.timestamp).toLocaleString() : ''}</span>
                    <span style="font-size:12px;color:var(--text-muted)">${esc(r.message || '')}</span>
                </div>
            </div>`).join('');
    }

    // ================================================================
    // Utilities
    // ================================================================
    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }

    /** Safely extract error message from API response data. */
    function errMsg(data) {
        if (typeof data === 'string') return data;
        return (data && data.message) || 'Unknown error';
    }

    function debounce(fn, ms) {
        let timer;
        return function () {
            clearTimeout(timer);
            timer = setTimeout(fn, ms);
        };
    }

    // ================================================================
    // Load Monaco Editor from CDN (async, optional)
    // ================================================================
    function loadMonaco() {
        const script = document.createElement('script');
        script.src = 'https://cdn.jsdelivr.net/npm/monaco-editor@0.45.0/min/vs/loader.js';
        script.onload = () => {
            require.config({ paths: { vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.45.0/min/vs' } });
            require(['vs/editor/editor.main'], () => {
                // Monaco loaded — will be used when editor view is activated
            });
        };
        script.onerror = () => {
            // Monaco CDN failed — editor will silently fallback to <textarea>
            console.warn('Monaco Editor CDN failed to load. Using textarea fallback.');
        };
        document.head.appendChild(script);
    }

    // ================================================================
    // Init
    // ================================================================
    loadDashboard();
    loadMonaco();
})();
