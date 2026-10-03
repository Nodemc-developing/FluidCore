/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import java.util.concurrent.ConcurrentHashMap;

/** Request identity and view identity are checked before a delayed result may replace a window. */
final class DeferredMenuOpen<K, V> {
    static final class Request<K, V> {
        final K key;
        final V view;
        private Request(K key, V view) { this.key = key; this.view = view; }
    }
    private final ConcurrentHashMap<K, Request<K, V>> pending = new ConcurrentHashMap<>();
    Request<K, V> begin(K key, V view) {
        Request<K, V> request = new Request<>(key, view);
        pending.put(key, request);
        return request;
    }
    boolean consume(Request<K, V> request, V currentView) {
        return request != null && request.view == currentView && pending.remove(request.key, request);
    }
    void cancel(Request<K, V> request) { if (request != null) pending.remove(request.key, request); }
    void cancel(K key) { pending.remove(key); }
    void clear() { pending.clear(); }
}
