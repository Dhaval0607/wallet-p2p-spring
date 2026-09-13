package com.dhaval.wallet.obs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * A fixed-size in-memory buffer of the most recent log lines, plus fan-out to
 * live subscribers.
 *
 * <p>Bounded memory is the whole point: a free-tier container has ~512MB and a
 * burst emits thousands of lines a second. Old lines are dropped and slow
 * subscribers are dropped -- logging must never block or exhaust the heap on the
 * money path.
 *
 * <p>A single static instance is held here rather than in the Spring context,
 * because the logback appender that feeds it is constructed by the logging
 * system long before any application context exists.
 */
public final class LogRing {

    private static final LogRing INSTANCE = new LogRing(5000);

    public static LogRing get() {
        return INSTANCE;
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final String[] buffer;
    private final int size;
    private int next;
    private int count;

    private final Map<Object, Consumer<String>> subscribers = new ConcurrentHashMap<>();

    LogRing(int size) {
        this.size = Math.max(1, size);
        this.buffer = new String[this.size];
    }

    /** Appends one JSON line and fans it out to live tailers. */
    public void append(String line) {
        lock.writeLock().lock();
        try {
            buffer[next] = line;
            next = (next + 1) % size;
            if (count < size) {
                count++;
            }
        } finally {
            lock.writeLock().unlock();
        }

        // Never let a stuck subscriber take down a request thread.
        subscribers.forEach((key, sink) -> {
            try {
                sink.accept(line);
            } catch (RuntimeException e) {
                subscribers.remove(key);
            }
        });
    }

    /** Returns up to {@code n} of the most recent lines, oldest first. */
    public List<String> snapshot(int n) {
        lock.readLock().lock();
        try {
            int take = (n <= 0 || n > count) ? count : n;
            List<String> out = new ArrayList<>(take);
            int start = ((next - take) % size + size) % size;
            for (int i = 0; i < take; i++) {
                String line = buffer[(start + i) % size];
                if (line != null) {
                    out.add(line);
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void subscribe(Object key, Consumer<String> sink) {
        subscribers.put(key, sink);
    }

    public void unsubscribe(Object key) {
        subscribers.remove(key);
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
