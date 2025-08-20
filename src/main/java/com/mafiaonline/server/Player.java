package com.mafiaonline.server;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Player model: id, name, role, alive, handler reference (set later)
 */
public class Player {
    private static final AtomicInteger ID_GEN = new AtomicInteger(1);

    private final int id;
    private final String name;
    private Role role;
    private boolean alive;
    private transient PlayerHandler handler;

    public Player(String name) {
        this.id = ID_GEN.getAndIncrement();
        this.name = name;
        this.alive = true;
    }

    public int getId() { return id; }
    public String getName() { return name; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
    public void kill() { this.alive = false; }

    public PlayerHandler getHandler() { return handler; }
    public void setHandler(PlayerHandler handler) { this.handler = handler; }

    @Override
    public String toString() {
        return name + " [" + (alive ? "Alive" : "Dead") + "] " + (role != null ? role : "UNASSIGNED");
    }
}
