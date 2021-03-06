/**
 * Copyright © 2016 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.extensions.core.plugin.telemetry;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.kv.*;
import org.thingsboard.server.common.msg.cluster.ServerAddress;
import org.thingsboard.server.extensions.api.plugins.PluginContext;
import org.thingsboard.server.extensions.core.plugin.telemetry.handlers.TelemetryRpcMsgHandler;
import org.thingsboard.server.extensions.core.plugin.telemetry.handlers.TelemetryWebsocketMsgHandler;
import org.thingsboard.server.extensions.core.plugin.telemetry.sub.Subscription;
import org.thingsboard.server.extensions.core.plugin.telemetry.sub.SubscriptionState;
import org.thingsboard.server.extensions.core.plugin.telemetry.sub.SubscriptionType;
import org.thingsboard.server.extensions.core.plugin.telemetry.sub.SubscriptionUpdate;

import java.util.*;
import java.util.function.Function;

/**
 * @author Andrew Shvayka
 */
@Slf4j
public class SubscriptionManager {

    private final Map<DeviceId, Set<Subscription>> subscriptionsByDeviceId = new HashMap<>();

    private final Map<String, Map<Integer, Subscription>> subscriptionsByWsSessionId = new HashMap<>();

    @Setter
    private TelemetryWebsocketMsgHandler websocketHandler;
    @Setter
    private TelemetryRpcMsgHandler rpcHandler;

    public void addLocalWsSubscription(PluginContext ctx, String sessionId, DeviceId deviceId, SubscriptionState sub) {
        Optional<ServerAddress> server = ctx.resolve(deviceId);
        Subscription subscription;
        if (server.isPresent()) {
            ServerAddress address = server.get();
            log.trace("[{}] Forwarding subscription [{}] for device [{}] to [{}]", sessionId, sub.getSubscriptionId(), deviceId, address);
            subscription = new Subscription(sub, true, address);
            rpcHandler.onNewSubscription(ctx, address, sessionId, subscription);
        } else {
            log.trace("[{}] Registering local subscription [{}] for device [{}]", sessionId, sub.getSubscriptionId(), deviceId);
            subscription = new Subscription(sub, true);
        }
        registerSubscription(sessionId, deviceId, subscription);
    }

    public void addRemoteWsSubscription(PluginContext ctx, ServerAddress address, String sessionId, Subscription subscription) {
        DeviceId deviceId = subscription.getDeviceId();
        log.trace("[{}] Registering remote subscription [{}] for device [{}] to [{}]", sessionId, subscription.getSubscriptionId(), deviceId, address);
        registerSubscription(sessionId, deviceId, subscription);
        List<TsKvEntry> missedUpdates = new ArrayList<>();
        if (subscription.getType() == SubscriptionType.ATTRIBUTES) {
            subscription.getKeyStates().entrySet().stream().forEach(e -> {
                        Optional<AttributeKvEntry> latestOpt = ctx.loadAttribute(deviceId, DataConstants.CLIENT_SCOPE, e.getKey());
                        if (latestOpt.isPresent()) {
                            AttributeKvEntry latestEntry = latestOpt.get();
                            if (latestEntry.getLastUpdateTs() > e.getValue()) {
                                missedUpdates.add(new BasicTsKvEntry(latestEntry.getLastUpdateTs(), latestEntry));
                            }
                        }
                    }
            );
        } else if (subscription.getType() == SubscriptionType.TIMESERIES) {
            long curTs = System.currentTimeMillis();
            subscription.getKeyStates().entrySet().forEach(e -> {
                TsKvQuery query = new BaseTsKvQuery(e.getKey(), e.getValue() + 1L, curTs);
                missedUpdates.addAll(ctx.loadTimeseries(deviceId, query));
            });
        }
        if (!missedUpdates.isEmpty()) {
            rpcHandler.onSubscriptionUpdate(ctx, address, sessionId, new SubscriptionUpdate(subscription.getSubscriptionId(), missedUpdates));
        }
    }

    private void registerSubscription(String sessionId, DeviceId deviceId, Subscription subscription) {
        Set<Subscription> deviceSubscriptions = subscriptionsByDeviceId.get(subscription.getDeviceId());
        if (deviceSubscriptions == null) {
            deviceSubscriptions = new HashSet<>();
            subscriptionsByDeviceId.put(deviceId, deviceSubscriptions);
        }
        deviceSubscriptions.add(subscription);
        Map<Integer, Subscription> sessionSubscriptions = subscriptionsByWsSessionId.get(sessionId);
        if (sessionSubscriptions == null) {
            sessionSubscriptions = new HashMap<>();
            subscriptionsByWsSessionId.put(sessionId, sessionSubscriptions);
        }
        sessionSubscriptions.put(subscription.getSubscriptionId(), subscription);
    }

    public void removeSubscription(PluginContext ctx, String sessionId, Integer subscriptionId) {
        log.debug("[{}][{}] Going to remove subscription.", sessionId, subscriptionId);
        Map<Integer, Subscription> sessionSubscriptions = subscriptionsByWsSessionId.get(sessionId);
        if (sessionSubscriptions != null) {
            Subscription subscription = sessionSubscriptions.remove(subscriptionId);
            if (subscription != null) {
                DeviceId deviceId = subscription.getDeviceId();
                if (subscription.isLocal() && subscription.getServer() != null) {
                    rpcHandler.onSubscriptionClose(ctx, subscription.getServer(), sessionId, subscription.getSubscriptionId());
                }
                if (sessionSubscriptions.isEmpty()) {
                    log.debug("[{}] Removed last subscription for particular session.", sessionId);
                    subscriptionsByWsSessionId.remove(sessionId);
                } else {
                    log.debug("[{}] Removed session subscription.", sessionId);
                }
                Set<Subscription> deviceSubscriptions = subscriptionsByDeviceId.get(deviceId);
                if (deviceSubscriptions != null) {
                    boolean result = deviceSubscriptions.remove(subscription);
                    if (result) {
                        if (deviceSubscriptions.size() == 0) {
                            log.debug("[{}] Removed last subscription for particular device.", sessionId);
                            subscriptionsByDeviceId.remove(deviceId);
                        } else {
                            log.debug("[{}] Removed device subscription.", sessionId);
                        }
                    } else {
                        log.debug("[{}] Subscription not found!", sessionId);
                    }
                } else {
                    log.debug("[{}] No device subscriptions found!", sessionId);
                }
            } else {
                log.debug("[{}][{}] Subscription not found!", sessionId, subscriptionId);
            }
        } else {
            log.debug("[{}] No session subscriptions found!", sessionId);
        }
    }

    public void onLocalSubscriptionUpdate(PluginContext ctx, DeviceId deviceId, SubscriptionType type, Function<Subscription, List<TsKvEntry>> f) {
        Set<Subscription> deviceSubscriptions = subscriptionsByDeviceId.get(deviceId);
        if (deviceSubscriptions != null) {
            deviceSubscriptions.stream().filter(s -> type == s.getType()).forEach(s -> {
                String sessionId = s.getWsSessionId();
                List<TsKvEntry> subscriptionUpdate = f.apply(s);
                if (!subscriptionUpdate.isEmpty()) {
                    SubscriptionUpdate update = new SubscriptionUpdate(s.getSubscriptionId(), subscriptionUpdate);
                    if (s.isLocal()) {
                        updateSubscriptionState(sessionId, s, update);
                        websocketHandler.sendWsMsg(ctx, sessionId, update);
                    } else {
                        rpcHandler.onSubscriptionUpdate(ctx, s.getServer(), sessionId, update);
                    }
                }
            });
        } else {
            log.debug("[{}] No device subscriptions to process!", deviceId);
        }
    }

    public void onRemoteSubscriptionUpdate(PluginContext ctx, String sessionId, SubscriptionUpdate update) {
        log.trace("[{}] Processing remote subscription onUpdate [{}]", sessionId, update);
        Optional<Subscription> subOpt = getSubscription(sessionId, update.getSubscriptionId());
        if (subOpt.isPresent()) {
            updateSubscriptionState(sessionId, subOpt.get(), update);
            websocketHandler.sendWsMsg(ctx, sessionId, update);
        }
    }

    private void updateSubscriptionState(String sessionId, Subscription subState, SubscriptionUpdate update) {
        log.trace("[{}] updating subscription state {} using onUpdate {}", sessionId, subState, update);
        update.getLatestValues().entrySet().forEach(e -> subState.setKeyState(e.getKey(), e.getValue()));
    }

    private Optional<Subscription> getSubscription(String sessionId, int subscriptionId) {
        Subscription state = null;
        Map<Integer, Subscription> subMap = subscriptionsByWsSessionId.get(sessionId);
        if (subMap != null) {
            state = subMap.get(subscriptionId);
        }
        return Optional.ofNullable(state);
    }

    public void cleanupLocalWsSessionSubscriptions(PluginContext ctx, String sessionId) {
        cleanupWsSessionSubscriptions(ctx, sessionId, true);
    }

    public void cleanupRemoteWsSessionSubscriptions(PluginContext ctx, String sessionId) {
        cleanupWsSessionSubscriptions(ctx, sessionId, false);
    }

    private void cleanupWsSessionSubscriptions(PluginContext ctx, String sessionId, boolean localSession) {
        log.debug("[{}] Removing all subscriptions for particular session.", sessionId);
        Map<Integer, Subscription> sessionSubscriptions = subscriptionsByWsSessionId.get(sessionId);
        if (sessionSubscriptions != null) {
            int sessionSubscriptionSize = sessionSubscriptions.size();

            for (Subscription subscription : sessionSubscriptions.values()) {
                DeviceId deviceId = subscription.getDeviceId();
                Set<Subscription> deviceSubscriptions = subscriptionsByDeviceId.get(deviceId);
                deviceSubscriptions.remove(subscription);
                if (deviceSubscriptions.isEmpty()) {
                    subscriptionsByDeviceId.remove(deviceId);
                }
            }
            subscriptionsByWsSessionId.remove(sessionId);
            log.debug("[{}] Removed {} subscriptions for particular session.", sessionId, sessionSubscriptionSize);

            if (localSession) {
                Set<ServerAddress> affectedServers = new HashSet<>();
                for (Subscription subscription : sessionSubscriptions.values()) {
                    if (subscription.getServer() != null) {
                        affectedServers.add(subscription.getServer());
                    }
                }
                for (ServerAddress address : affectedServers) {
                    log.debug("[{}] Going to onSubscriptionUpdate [{}] server about session close event", sessionId, address);
                    rpcHandler.onSessionClose(ctx, address, sessionId);
                }
            }
        } else {
            log.debug("[{}] No subscriptions found!", sessionId);
        }
    }

    public void onClusterUpdate(PluginContext ctx) {
        log.trace("Processing cluster onUpdate msg!");
        Iterator<Map.Entry<DeviceId, Set<Subscription>>> deviceIterator = subscriptionsByDeviceId.entrySet().iterator();
        while (deviceIterator.hasNext()) {
            Map.Entry<DeviceId, Set<Subscription>> e = deviceIterator.next();
            Set<Subscription> subscriptions = e.getValue();
            Optional<ServerAddress> newAddressOptional = ctx.resolve(e.getKey());
            if (newAddressOptional.isPresent()) {
                ServerAddress newAddress = newAddressOptional.get();
                Iterator<Subscription> subscriptionIterator = subscriptions.iterator();
                while (subscriptionIterator.hasNext()) {
                    Subscription s = subscriptionIterator.next();
                    if (s.isLocal()) {
                        if (!newAddress.equals(s.getServer())) {
                            log.trace("[{}] Local subscription is now handled on new server [{}]", s.getWsSessionId(), newAddress);
                            s.setServer(newAddress);
                            rpcHandler.onNewSubscription(ctx, newAddress, s.getWsSessionId(), s);
                        }
                    } else {
                        log.trace("[{}] Remote subscription is now handled on new server address: [{}]", s.getWsSessionId(), newAddress);
                        subscriptionIterator.remove();

                        //TODO: onUpdate state of subscription by WsSessionId and other maps.
                    }
                }
            } else {
                Iterator<Subscription> subscriptionIterator = subscriptions.iterator();
                while (subscriptionIterator.hasNext()) {
                    Subscription s = subscriptionIterator.next();
                    if (s.isLocal()) {
                        if (s.getServer() != null) {
                            log.trace("[{}] Local subscription is no longer handled on remote server address [{}]", s.getWsSessionId(), s.getServer());
                            s.setServer(null);
                        }
                    } else {
                        log.trace("[{}] Remote subscription is on up to date server address.", s.getWsSessionId());
                    }
                }
            }
            if (subscriptions.size() == 0) {
                log.trace("[{}] No more subscriptions for this device on current server.", e.getKey());
                deviceIterator.remove();
            }
        }
    }

    public void clear() {
        subscriptionsByWsSessionId.clear();
        subscriptionsByDeviceId.clear();
    }
}