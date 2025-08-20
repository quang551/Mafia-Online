package com.mafiaonline.server;

import java.util.*;
import java.util.stream.Collectors;

public class GameRoom {
    private final Map<String, Player> players = new LinkedHashMap<>(); // name -> Player
    private final Map<String, PlayerHandler> handlers = new HashMap<>(); // name -> handler
    private final PhaseManager phaseManager;

    private boolean gameStarted = false;
    private GameState state = GameState.LOBBY;

    public GameRoom() {
        this.phaseManager = new PhaseManager(this);
    }

    // ---------- Player management ----------
    public synchronized void addPlayer(String name) {
        if (players.containsKey(name)) {
            System.out.println("[GameRoom] Tên " + name + " đã tồn tại.");
            return;
        }
        Player p = new Player(name);
        players.put(name, p);
        System.out.println("[GameRoom] Player added: " + p);
    }

    public synchronized void addPlayer(String name, PlayerHandler handler) {
        if (players.containsKey(name)) {
            handler.sendMessage("⚠️ Tên '" + name + "' đã được sử dụng, vui lòng đổi tên và kết nối lại.");
            return;
        }
        Player p = new Player(name);
        p.setHandler(handler);
        players.put(name, p);
        handlers.put(name, handler);
        System.out.println("[GameRoom] Player added (with handler): " + p);
        broadcast("📥 Người chơi " + name + " đã tham gia (" + players.size() + " players).");
    }

    public synchronized void removePlayer(String name) {
        Player removed = players.remove(name);
        handlers.remove(name);
        if (removed != null) {
            System.out.println("[GameRoom] Player removed: " + removed.getName());
            broadcast("📤 Người chơi " + name + " đã rời phòng.");
            checkWinCondition();
        }
    }

    public synchronized Player getPlayer(String name) { return players.get(name); }
    public synchronized Collection<Player> getPlayersAll() { return Collections.unmodifiableCollection(players.values()); }
    public synchronized List<Player> getPlayersAlive() {
        return players.values().stream().filter(Player::isAlive).collect(Collectors.toList());
    }
    public synchronized boolean isGameStarted() { return gameStarted; }
    public synchronized GameState getState() { return state; }
    public PhaseManager getPhaseManager() { return phaseManager; }

    public synchronized void setState(GameState newState) {
        this.state = newState;
        System.out.println("[GameRoom] State -> " + newState);
    }

    // ---------- Start game & assign roles ----------
    public synchronized void startGame() {
        if (gameStarted) {
            broadcast("⚠️ Game đã bắt đầu.");
            return;
        }
        if (players.size() < 5) {
            broadcast("❌ Cần ít nhất 5 người để bắt đầu game (hiện: " + players.size() + ").");
            return;
        }

        gameStarted = true;
        state = GameState.DAY;

        // Build role pool hợp lý theo số người chơi
List<Role> pool = new ArrayList<>();
int playerCount = players.size();

// Xác định số Mafia (5–6: 1 mafia, 7–8: 2 mafia, 9–10: 3 mafia)
int mafiaCount = 1;
if (playerCount >= 7) mafiaCount = 2;
if (playerCount >= 9) mafiaCount = 3;

// Thêm Mafia
for (int i = 0; i < mafiaCount; i++) {
    pool.add(Role.MAFIA);
}

// Thêm các vai đặc biệt (tối đa 1 mỗi loại, nếu còn slot)
if (pool.size() < playerCount) pool.add(Role.DOCTOR);
if (pool.size() < playerCount) pool.add(Role.DETECTIVE);
if (pool.size() < playerCount) pool.add(Role.BODYGUARD);
if (pool.size() < playerCount) pool.add(Role.JESTER);

// Các slot còn lại là Dân làng
while (pool.size() < playerCount) {
    pool.add(Role.VILLAGER);
}

// Trộn ngẫu nhiên danh sách role
Collections.shuffle(pool);
Iterator<Role> it = pool.iterator();
for (Player p : players.values()) {
    Role r = it.next();
    p.setRole(r);
    PlayerHandler h = p.getHandler();
    if (h != null) {
        h.setRole(r);
        h.sendMessage("🎭 Role của bạn: " + r + " — " + r.getDescription());
    } else {
        System.out.println("[GameRoom] " + p.getName() + " assigned role " + r);
    }
}


        Collections.shuffle(pool);
        Iterator<Role> it = pool.iterator();
        for (Player p : players.values()) {
            Role r = it.next();
            p.setRole(r);
            PlayerHandler h = p.getHandler();
            if (h != null) {
                h.setRole(r);
                h.sendMessage("🎭 Role của bạn: " + r + " — " + r.getDescription());
            } else {
                System.out.println("[GameRoom] " + p.getName() + " assigned role " + r);
            }
        }

        broadcast("✅ Trò chơi đã bắt đầu! Roles đã được phân phối. Hiện là Pha DAY.");
        System.out.println("=== Role assignment ===");
        for (Player p : players.values()) System.out.println(" - " + p.getName() + " -> " + p.getRole());

        // start day phase
        phaseManager.startDay();
    }

    // ---------- wrappers to PhaseManager ----------
    public synchronized void startDayPhase() { phaseManager.startDay(); }
    public synchronized void endDayPhase() { phaseManager.endDay(); }
    public synchronized void castVote(String voter, String target) { phaseManager.castVote(voter, target); }
    public synchronized void startNightPhase() { phaseManager.startNight(); }
    public synchronized void endNightPhase() { phaseManager.endNight(); }
    public synchronized void recordNightAction(String actor, String target) { phaseManager.recordNightAction(actor, target); }

    // ---------- Kill & Win ----------
    public synchronized void killPlayer(String name) {
        Player p = players.get(name);
        if (p != null && p.isAlive()) {
            p.kill();
            PlayerHandler h = p.getHandler();
            if (h != null) h.sendMessage("☠️ Bạn đã chết!");
            broadcast("💀 " + name + " đã bị loại khỏi game.");
            checkWinCondition();
        }
    }

    public synchronized void checkWinCondition() {
        if (!gameStarted) return;

        long mafiaAlive = players.values().stream().filter(p -> p.isAlive() && p.getRole() == Role.MAFIA).count();
        long villagersAlive = players.values().stream().filter(p -> p.isAlive() && p.getRole() != Role.MAFIA).count();

        if (mafiaAlive == 0) {
            broadcast("🎉 DÂN LÀNG THẮNG! Tất cả Mafia đã bị loại.");
            endGame();
        } else if (mafiaAlive >= villagersAlive) {
            broadcast("😈 MAFIA THẮNG! Mafia đã áp đảo.");
            endGame();
        } else {
            System.out.println("[GameRoom] Game continues. mafiaAlive=" + mafiaAlive + ", villagersAlive=" + villagersAlive);
        }
    }

    public synchronized void endGame() {
        this.gameStarted = false;
        this.state = GameState.END;

        for (Player p : players.values()) {
            p.setRole(Role.UNASSIGNED);
            p.setAlive(true);
        }
        broadcast("🏁 Trò chơi kết thúc.");
    }

    // ---------------- small helpers ----------------
    public synchronized boolean isAlive(String name) {
        Player p = players.get(name);
        return p != null && p.isAlive();
    }

    public synchronized void privateMessage(String name, String msg) {
        sendToPlayer(name, msg);
    }

    public synchronized Role getPlayerRole(String name) {
        Player p = players.get(name);
        return (p != null) ? p.getRole() : null;
    }

    // ---------- messaging ----------
    public synchronized void sendToPlayer(String name, String msg) {
        PlayerHandler h = handlers.get(name);
        if (h != null) h.sendMessage(msg);
    }

    public synchronized void broadcast(String msg) {
        System.out.println("[Broadcast] " + msg);
        for (PlayerHandler h : handlers.values()) h.sendMessage(msg);
    }

    // ---------- Utilities ----------
    public synchronized int getAliveCount() {
        return (int) players.values().stream().filter(Player::isAlive).count();
    }

    public synchronized void printPlayers() {
        System.out.println("=== Player list ===");
        for (Player p : players.values()) System.out.println(" - " + p);
    }
}
