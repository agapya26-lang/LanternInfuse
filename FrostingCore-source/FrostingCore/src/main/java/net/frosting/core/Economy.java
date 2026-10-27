package net.frosting.core;

import java.util.UUID;

/** Money + shards, by UUID (works for offline players too). */
public final class Economy {
    private final PlayerStore store;

    public Economy(PlayerStore store) {
        this.store = store;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public double balance(UUID id) {
        return store.get(id).money;
    }

    public boolean has(UUID id, double amount) {
        return store.get(id).money + 1e-9 >= amount;
    }

    public void add(UUID id, double amount) {
        PlayerStore.Data d = store.get(id);
        d.money = round(d.money + amount);
        store.dirty();
    }

    public boolean take(UUID id, double amount) {
        if (!has(id, amount)) return false;
        PlayerStore.Data d = store.get(id);
        d.money = round(d.money - amount);
        store.dirty();
        return true;
    }

    public void set(UUID id, double amount) {
        store.get(id).money = round(Math.max(0, amount));
        store.dirty();
    }

    public double shards(UUID id) {
        return store.get(id).shards;
    }

    public void addShards(UUID id, double amount) {
        PlayerStore.Data d = store.get(id);
        d.shards = round(d.shards + amount);
        store.dirty();
    }

    public boolean takeShards(UUID id, double amount) {
        PlayerStore.Data d = store.get(id);
        if (d.shards + 1e-9 < amount) return false;
        d.shards = round(d.shards - amount);
        store.dirty();
        return true;
    }

    public void setShards(UUID id, double amount) {
        store.get(id).shards = round(Math.max(0, amount));
        store.dirty();
    }
}
