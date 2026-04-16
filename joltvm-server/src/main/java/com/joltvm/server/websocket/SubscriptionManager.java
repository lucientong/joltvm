/*
 * Copyright 2026 lucientong.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.joltvm.server.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages WebSocket subscriptions and periodically pushes data to subscribed channels.
 *
 * <p>Protocol:
 * <pre>
 * // Client → Server
 * {"type": "subscribe", "channel": "threads.top", "params": {"interval": 5000}}
 * {"type": "unsubscribe", "channel": "threads.top"}
 * {"type": "ping"}
 *
 * // Server → Client
 * {"type": "data", "channel": "threads.top", "payload": [...]}
 * {"type": "subscribed", "channel": "threads.top"}
 * {"type": "unsubscribed", "channel": "threads.top"}
 * {"type": "pong"}
 * {"type": "error", "message": "..."}
 * </pre>
 *
 * <p>Supported channels: {@code threads.top}, {@code gc.stats}, {@code jvm.memory}
 */
public class SubscriptionManager {

    private static final Logger LOG = Logger.getLogger(SubscriptionManager.class.getName());
    private static final Gson GSON = new Gson();

    /** All connected WebSocket channels. */
    private final ChannelGroup allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /** channel name → set of subscribed Channel objects. */
    private final ConcurrentHashMap<String, Set<Channel>> subscriptions = new ConcurrentHashMap<>();

    /** Scheduler for periodic push tasks. */
    private final ScheduledExecutorService scheduler;

    /** Active push tasks per channel name. */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pushTasks = new ConcurrentHashMap<>();

    /** Data providers for each channel. */
    private final ConcurrentHashMap<String, DataProvider> providers = new ConcurrentHashMap<>();

    /** Supported channel names. */
    public static final Set<String> SUPPORTED_CHANNELS = Set.of(
            "threads.top", "gc.stats", "jvm.memory");

    @FunctionalInterface
    public interface DataProvider {
        Object getData();
    }

    public SubscriptionManager() {
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "joltvm-ws-push");
            t.setDaemon(true);
            return t;
        });
    }

    /** Registers a data provider for a channel. */
    public void registerProvider(String channel, DataProvider provider) {
        providers.put(channel, provider);
    }

    /** Called when a new WebSocket connection is established. */
    public void onConnect(Channel channel) {
        allChannels.add(channel);
        LOG.fine("WebSocket connected: " + channel.remoteAddress());
    }

    /** Called when a WebSocket connection is closed. */
    public void onDisconnect(Channel channel) {
        allChannels.remove(channel);
        // Remove from all subscriptions
        for (Set<Channel> subscribers : subscriptions.values()) {
            subscribers.remove(channel);
        }
        LOG.fine("WebSocket disconnected: " + channel.remoteAddress());
    }

    /** Processes an incoming WebSocket text message. */
    public void onMessage(Channel channel, String text) {
        try {
            JsonObject msg = GSON.fromJson(text, JsonObject.class);
            if (msg == null || !msg.has("type")) {
                sendError(channel, "Missing 'type' field");
                return;
            }

            String type = msg.get("type").getAsString();
            switch (type) {
                case "subscribe" -> handleSubscribe(channel, msg);
                case "unsubscribe" -> handleUnsubscribe(channel, msg);
                case "ping" -> sendFrame(channel, Map.of("type", "pong"));
                default -> sendError(channel, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            sendError(channel, "Invalid message: " + e.getMessage());
        }
    }

    private void handleSubscribe(Channel channel, JsonObject msg) {
        if (!msg.has("channel")) {
            sendError(channel, "Missing 'channel' field");
            return;
        }
        String channelName = msg.get("channel").getAsString();
        if (!SUPPORTED_CHANNELS.contains(channelName)) {
            sendError(channel, "Unknown channel: " + channelName + ". Supported: " + SUPPORTED_CHANNELS);
            return;
        }

        Set<Channel> subscribers = subscriptions.computeIfAbsent(channelName, k -> new CopyOnWriteArraySet<>());
        subscribers.add(channel);

        // Start push task if not already running
        startPushTaskIfNeeded(channelName);

        sendFrame(channel, Map.of("type", "subscribed", "channel", channelName));
        LOG.fine("Subscribed: " + channel.remoteAddress() + " → " + channelName);
    }

    private void handleUnsubscribe(Channel channel, JsonObject msg) {
        if (!msg.has("channel")) {
            sendError(channel, "Missing 'channel' field");
            return;
        }
        String channelName = msg.get("channel").getAsString();
        Set<Channel> subscribers = subscriptions.get(channelName);
        if (subscribers != null) {
            subscribers.remove(channel);
            if (subscribers.isEmpty()) {
                stopPushTask(channelName);
            }
        }
        sendFrame(channel, Map.of("type", "unsubscribed", "channel", channelName));
    }

    private void startPushTaskIfNeeded(String channelName) {
        if (pushTasks.containsKey(channelName)) return;

        DataProvider provider = providers.get(channelName);
        if (provider == null) return;

        long intervalMs = getDefaultInterval(channelName);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                Set<Channel> subscribers = subscriptions.get(channelName);
                if (subscribers == null || subscribers.isEmpty()) return;

                Object data = provider.getData();
                Map<String, Object> frame = Map.of(
                        "type", "data",
                        "channel", channelName,
                        "payload", data != null ? data : Map.of());

                String json = GSON.toJson(frame);
                TextWebSocketFrame wsFrame = new TextWebSocketFrame(json);

                for (Channel ch : subscribers) {
                    if (ch.isActive()) {
                        ch.writeAndFlush(wsFrame.retainedDuplicate());
                    }
                }
                wsFrame.release();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Push error for channel " + channelName, e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        pushTasks.put(channelName, future);
    }

    private void stopPushTask(String channelName) {
        ScheduledFuture<?> future = pushTasks.remove(channelName);
        if (future != null) {
            future.cancel(false);
        }
    }

    private long getDefaultInterval(String channelName) {
        return switch (channelName) {
            case "threads.top" -> 5000;
            case "gc.stats" -> 10000;
            case "jvm.memory" -> 5000;
            default -> 5000;
        };
    }

    private void sendFrame(Channel channel, Map<String, Object> data) {
        if (channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(GSON.toJson(data)));
        }
    }

    private void sendError(Channel channel, String message) {
        sendFrame(channel, Map.of("type", "error", "message", message));
    }

    /** Returns the number of connected WebSocket clients. */
    public int getConnectionCount() {
        return allChannels.size();
    }

    /** Returns subscription counts per channel. */
    public Map<String, Integer> getSubscriptionCounts() {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        for (Map.Entry<String, Set<Channel>> entry : subscriptions.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
        }
        return counts;
    }

    /** Shuts down the push scheduler. */
    public void shutdown() {
        for (ScheduledFuture<?> future : pushTasks.values()) {
            future.cancel(true);
        }
        pushTasks.clear();
        scheduler.shutdownNow();
        allChannels.close();
    }
}
