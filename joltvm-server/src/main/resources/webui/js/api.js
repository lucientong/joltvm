/**
 * JoltVM Web IDE — API Client
 * Copyright 2026 lucientong. Apache License 2.0
 *
 * Provides methods to interact with the JoltVM REST API.
 * Supports token-based authentication when enabled.
 */
const JoltAPI = (() => {
    const BASE = '';  // Same origin — served by Netty
    let _token = localStorage.getItem('joltvm_token') || null;

    function setToken(token) {
        _token = token;
        if (token) {
            localStorage.setItem('joltvm_token', token);
        } else {
            localStorage.removeItem('joltvm_token');
        }
    }

    function getToken() { return _token; }

    async function request(method, path, body) {
        const opts = {
            method,
            headers: { 'Content-Type': 'application/json' }
        };
        if (_token && _token !== 'disabled') {
            opts.headers['Authorization'] = 'Bearer ' + _token;
        }
        if (body) opts.body = JSON.stringify(body);
        const res = await fetch(BASE + path, opts);
        const text = await res.text();
        try {
            return { ok: res.ok, status: res.status, data: JSON.parse(text) };
        } catch {
            return { ok: res.ok, status: res.status, data: text };
        }
    }

    return {
        // Auth
        setToken,
        getToken,
        login: (username, password) => request('POST', '/api/auth/login', { username, password }),
        authStatus: () => request('GET', '/api/auth/status'),
        logout: () => { setToken(null); },

        // Health
        health: () => request('GET', '/api/health'),

        // Classes
        listClasses: (page, size, pkg, search) => {
            const params = new URLSearchParams();
            if (page != null) params.set('page', page);
            if (size != null) params.set('size', size);
            if (pkg) params.set('package', pkg);
            if (search) params.set('search', search);
            return request('GET', '/api/classes?' + params.toString());
        },
        classDetail: (className) => request('GET', '/api/classes/' + encodeURIComponent(className)),
        classSource: (className) => request('GET', '/api/classes/' + encodeURIComponent(className) + '/source'),

        // Hot-Swap
        compile: (className, sourceCode) => request('POST', '/api/compile', { className, sourceCode }),
        hotswap: (className, sourceCode, reason) => request('POST', '/api/hotswap', { className, sourceCode, ...(reason ? { reason } : {}) }),
        rollback: (className, reason) => request('POST', '/api/rollback', { className, ...(reason ? { reason } : {}) }),
        hotswapHistory: () => request('GET', '/api/hotswap/history'),

        // Tracing
        traceStart: (body) => request('POST', '/api/trace/start', body),
        traceStop: (body) => request('POST', '/api/trace/stop', body || {}),
        traceRecords: (limit) => request('GET', '/api/trace/records' + (limit ? '?limit=' + limit : '')),
        traceFlameGraph: (view) => request('GET', '/api/trace/flamegraph' + (view ? '?view=' + encodeURIComponent(view) : '')),
        traceStatus: () => request('GET', '/api/trace/status'),

        // Spring
        springBeans: (page, size, pkg, search, stereotype) => {
            const params = new URLSearchParams();
            if (page != null) params.set('page', page);
            if (size != null) params.set('size', size);
            if (pkg) params.set('package', pkg);
            if (search) params.set('search', search);
            if (stereotype) params.set('stereotype', stereotype);
            return request('GET', '/api/spring/beans?' + params.toString());
        },
        springBeanDetail: (name) => request('GET', '/api/spring/beans/' + encodeURIComponent(name)),
        springMappings: (method, search) => {
            const params = new URLSearchParams();
            if (method) params.set('method', method);
            if (search) params.set('search', search);
            return request('GET', '/api/spring/mappings?' + params.toString());
        },
        springDependencies: () => request('GET', '/api/spring/dependencies'),
        springDependencyChain: (name) => request('GET', '/api/spring/dependencies/' + encodeURIComponent(name)),

        // Audit
        auditExport: (format) => request('GET', '/api/audit/export' + (format ? '?format=' + format : '')),

        // Threads
        threadList: (state) => request('GET', '/api/threads' + (state ? '?state=' + state : '')),
        threadTop: (n, interval) => {
            const params = new URLSearchParams();
            if (n != null) params.set('n', n);
            if (interval != null) params.set('interval', interval);
            return request('GET', '/api/threads/top?' + params.toString());
        },
        threadDetail: (id) => request('GET', '/api/threads/' + id),
        threadDeadlocks: () => request('GET', '/api/threads/deadlocks'),
        threadDump: () => request('GET', '/api/threads/dump'),

        // JVM Info
        jvmGc: () => request('GET', '/api/jvm/gc'),
        jvmSysProps: () => request('GET', '/api/jvm/sysprops'),
        jvmSysEnv: () => request('GET', '/api/jvm/sysenv'),
        jvmClasspath: () => request('GET', '/api/jvm/classpath')
    };
})();
