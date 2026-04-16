/**
 * JoltVM Web IDE — WebSocket Client
 * Copyright 2026 lucientong. Apache License 2.0
 *
 * Auto-reconnect WebSocket client with REST API fallback.
 * Provides subscribe/unsubscribe for real-time data channels.
 */
const JoltWS = (() => {
    'use strict';

    let ws = null;
    let reconnectTimer = null;
    let reconnectDelay = 1000;
    const MAX_RECONNECT_DELAY = 30000;
    const listeners = {}; // channel -> [callback]
    let connected = false;

    function getWsUrl() {
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const token = JoltAPI.getToken();
        let url = `${proto}//${location.host}/ws`;
        if (token && token !== 'disabled') {
            url += '?token=' + encodeURIComponent(token);
        }
        return url;
    }

    function connect() {
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
            return;
        }

        try {
            ws = new WebSocket(getWsUrl());
        } catch (e) {
            console.warn('WebSocket not supported, using REST fallback');
            updateIndicator(false);
            return;
        }

        ws.onopen = () => {
            connected = true;
            reconnectDelay = 1000;
            updateIndicator(true);
            // Re-subscribe to all active channels
            for (const channel of Object.keys(listeners)) {
                if (listeners[channel].length > 0) {
                    send({ type: 'subscribe', channel });
                }
            }
        };

        ws.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'data' && msg.channel) {
                    const cbs = listeners[msg.channel];
                    if (cbs) {
                        for (const cb of cbs) cb(msg.payload);
                    }
                }
            } catch (e) {
                console.warn('WS message parse error:', e);
            }
        };

        ws.onclose = () => {
            connected = false;
            updateIndicator(false);
            scheduleReconnect();
        };

        ws.onerror = () => {
            // onclose will fire after onerror
        };
    }

    function scheduleReconnect() {
        if (reconnectTimer) return;
        reconnectTimer = setTimeout(() => {
            reconnectTimer = null;
            reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY);
            connect();
        }, reconnectDelay);
    }

    function send(msg) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(msg));
        }
    }

    /**
     * Subscribe to a real-time data channel.
     * @param {string} channel - e.g., "threads.top", "gc.stats"
     * @param {function} callback - called with payload data
     */
    function subscribe(channel, callback) {
        if (!listeners[channel]) listeners[channel] = [];
        listeners[channel].push(callback);

        if (connected) {
            send({ type: 'subscribe', channel });
        }
    }

    /**
     * Unsubscribe from a channel.
     */
    function unsubscribe(channel, callback) {
        if (!listeners[channel]) return;
        listeners[channel] = listeners[channel].filter(cb => cb !== callback);

        if (listeners[channel].length === 0) {
            delete listeners[channel];
            if (connected) {
                send({ type: 'unsubscribe', channel });
            }
        }
    }

    function updateIndicator(isConnected) {
        const indicator = document.getElementById('wsIndicator');
        if (indicator) {
            indicator.className = 'ws-indicator ' + (isConnected ? 'ws-connected' : 'ws-disconnected');
            indicator.title = isConnected ? 'WebSocket connected' : 'WebSocket disconnected (REST fallback)';
        }
    }

    function isConnected() { return connected; }

    function disconnect() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
        if (ws) {
            ws.close();
            ws = null;
        }
        connected = false;
    }

    // Auto-connect on page load
    document.addEventListener('DOMContentLoaded', () => {
        connect();
    });

    return { connect, disconnect, subscribe, unsubscribe, isConnected, send };
})();
