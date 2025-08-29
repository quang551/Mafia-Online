package com.mafiaonline.server;

import java.util.UUID;

public class Player {
    private final String id;
    private final String name;
    private Role role = Role.UNASSIGNED;
    private boolean alive = true;
    private PlayerHandler handler = null;

    public Player(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    public synchronized Role getRole() { return role; }
    public synchronized void setRole(Role role) { this.role = role; }

    public synchronized boolean isAlive() { return alive; }
    public synchronized void setAlive(boolean alive) { this.alive = alive; }
    public synchronized void kill() { this.alive = false; }

    public synchronized PlayerHandler getHandler() { return handler; }
    public synchronized void setHandler(PlayerHandler handler) { this.handler = handler; }

    @Override
    public String toString() {
        return name + " [" + (alive ? "Alive" : "Dead") + "] " + role;
    }
}
