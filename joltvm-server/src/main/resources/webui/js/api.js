/**
 * JoltVM Web IDE — API Client
 * Copyright 2026 lucientong. Apache License 2.0
 *
 * Provides methods to interact with the JoltVM REST API.
 */
const JoltAPI = (() => {
    const BASE = '';  // Same origin — served by Netty

    async function request(method, path, body) {
        const opts = {
            method,
            headers: { 'Content-Type': 'application/json' }
        };
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
        compile: (className, source) => request('POST', '/api/compile', { className, source }),
        hotswap: (className, source) => request('POST', '/api/hotswap', { className, source }),
        rollback: (className) => request('POST', '/api/rollback', { className }),
        hotswapHistory: () => request('GET', '/api/hotswap/history'),

        // Tracing
        traceStart: (body) => request('POST', '/api/trace/start', body),
        traceStop: (body) => request('POST', '/api/trace/stop', body || {}),
        traceRecords: (limit) => request('GET', '/api/trace/records' + (limit ? '?limit=' + limit : '')),
        traceFlameGraph: () => request('GET', '/api/trace/flamegraph'),
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
        springDependencyChain: (name) => request('GET', '/api/spring/dependencies/' + encodeURIComponent(name))
    };
})();
