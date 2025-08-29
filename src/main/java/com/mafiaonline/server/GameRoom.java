package com.mafiaonline.server;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GameRoom - quản lý người chơi, role, trạng thái game và giao tiếp với PhaseManager.
 * - Phân role theo số người (1/2/3 mafia tuỳ player count)
 * - Wrapper gọi PhaseManager (startDay/openVote/resolveDay/…)
 * - Thông báo riêng cho Mafia biết đồng đội
 * - promptPendingForPhaseForAll() để hỏi input theo phase
 * - shutdown() dọn scheduler khi tắt server
 */
public class GameRoom {

    private final Map<String, Player> players = new LinkedHashMap<>(); // name -> Player
    private final Map<String, PlayerHandler> handlers = new HashMap<>(); // name -> handler
    private final PhaseManager phaseManager;

    private boolean gameStarted = false;
    private GameState state = GameState.LOBBY;

    public GameRoom() {
        this.phaseManager = new PhaseManager(this);
    }

    /* ==================== Player management ==================== */

    public synchronized void addPlayer(String name) {
        if (players.containsKey(name)) {
            System.out.println("[GameRoom] Tên '" + name + "' đã tồn tại.");
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

    public synchronized Collection<Player> getPlayersAll() {
        return Collections.unmodifiableCollection(players.values());
    }

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

    /* ==================== Start game & assign roles ==================== */

    public synchronized void startGame() {
        if (gameStarted) {
            broadcast("⚠️ Game đã bắt đầu.");
            return;
        }
        if (players.size() < 3) {
            broadcast("❌ Cần ít nhất 3 người để bắt đầu game (hiện: " + players.size() + ").");
            return;
        }

        gameStarted = true;
        state = GameState.DAY;

        // Xây pool role theo số người
        List<Role> pool = new ArrayList<>();
        int playerCount = players.size();

        // Số mafia: 1 (<7), 2 (7–8), 3 (>=9)
        int mafiaCount = (playerCount >= 9) ? 3 : (playerCount >= 7 ? 2 : 1);
        for (int i = 0; i < mafiaCount; i++) pool.add(Role.MAFIA);

        // Thêm các vai đặc biệt tối đa 1
        if (pool.size() < playerCount) pool.add(Role.DOCTOR);
        if (pool.size() < playerCount) pool.add(Role.DETECTIVE);
        if (pool.size() < playerCount) pool.add(Role.BODYGUARD);
        if (pool.size() < playerCount) pool.add(Role.JESTER);

        // Phần còn lại là dân
        while (pool.size() < playerCount) pool.add(Role.VILLAGER);

        // Trộn & gán
        Collections.shuffle(pool);
        Iterator<Role> it = pool.iterator();

        for (Player p : players.values()) {
            Role r = it.next();
            p.setRole(r);
            p.setAlive(true);
            PlayerHandler h = p.getHandler();
            if (h != null) {
                h.setRole(r);
                h.sendMessage("🎭 Role của bạn: " + r + " — " + r.getDescription());
            } else {
                System.out.println("[GameRoom] " + p.getName() + " assigned role " + r);
            }
        }

        // Thông báo riêng cho Mafia biết đồng đội
        List<String> mafiaNames = players.values().stream()
                .filter(pl -> pl.getRole() == Role.MAFIA)
                .map(Player::getName)
                .toList();
        if (!mafiaNames.isEmpty()) {
            String team = String.join(", ", mafiaNames);
            for (String maf : mafiaNames) {
                sendToPlayer(maf, "🕵️‍♂️ Đồng đội Mafia của bạn: " + team);
            }
        }

        broadcast("✅ Trò chơi đã bắt đầu! Roles đã được phân phối. Bắt đầu Pha DAY (CHAT).");
        System.out.println("=== Role assignment ===");
        for (Player p : players.values()) System.out.println(" - " + p.getName() + " -> " + p.getRole());

        // Ngày: CHAT -> VOTE -> RESOLVE do PhaseManager điều phối
        phaseManager.startDay();
    }

    /* ==================== Wrappers tới PhaseManager ==================== */

    public synchronized void startDayPhase() { phaseManager.startDay(); }

    /** Admin ép mở giai đoạn vote ngay (bỏ qua phần CHAT còn lại) */
    public synchronized void openVotePhase() { phaseManager.openVotePhase(); }

    /** Admin/chốt tự động: hết vote → xử tử → sang đêm */
    public synchronized void resolveDayPhase() { phaseManager.resolveDay(); }

    /** Giữ tương thích cũ: endDay() => resolveDay() */
    public synchronized void endDayPhase() { phaseManager.endDay(); }

    public synchronized void castVote(String voter, String target) { phaseManager.castVote(voter, target); }

    public synchronized void startNightPhase() { phaseManager.startNight(); }

    public synchronized void endNightPhase() { phaseManager.endNight(); }

    public synchronized void recordNightAction(String actor, String target) { phaseManager.recordNightAction(actor, target); }

    /** Cho PlayerHandler kiểm tra để “vote bằng cách gõ tên” */
    public synchronized boolean isVotingOpen() {
        return phaseManager != null && phaseManager.isVotingOpen();
    }

    /* ==================== Kill & Win ==================== */

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

        // (tuỳ chọn) lộ role khi kết thúc
        String reveal = players.values().stream()
                .map(p -> p.getName() + " → " + p.getRole())
                .collect(Collectors.joining(", "));
        broadcast("🏁 Trò chơi kết thúc. Vai: " + reveal);

        // Reset về lobby
        for (Player p : players.values()) {
            p.setRole(Role.UNASSIGNED);
            p.setAlive(true);
        }
    }

    /* ==================== Messaging ==================== */

    public synchronized void sendToPlayer(String name, String msg) {
        PlayerHandler h = handlers.get(name);
        if (h != null) h.sendMessage(msg);
    }

    public synchronized void broadcast(String msg) {
        System.out.println("[Broadcast] " + msg);
        for (PlayerHandler h : handlers.values()) h.sendMessage(msg);
    }

    /* ==================== Small helpers ==================== */

    public synchronized boolean isAlive(String name) {
        Player p = players.get(name);
        return p != null && p.isAlive();
    }

    public synchronized int getAliveCount() {
        return (int) players.values().stream().filter(Player::isAlive).count();
    }

    public synchronized void printPlayers() {
        System.out.println("=== Player list ===");
        for (Player p : players.values()) System.out.println(" - " + p);
    }

    /** Lấy handler theo tên (nếu cần dùng riêng lẻ) */
    public synchronized PlayerHandler getHandler(String name) { return handlers.get(name); }

    /** Lấy tất cả handler (nếu cần lặp) */
    public synchronized Collection<PlayerHandler> getAllHandlers() {
        return Collections.unmodifiableCollection(handlers.values());
    }

    /** Gọi prompt pending cho tất cả player theo state hiện tại (ví dụ hiển thị "Bạn muốn vote ai?") */
    public synchronized void promptPendingForPhaseForAll() {
        GameState s = getState();
        for (PlayerHandler h : handlers.values()) {
            h.setPendingForPhase(s);
        }
    }

    /** Gọi khi tắt server để dừng scheduler an toàn */
    public synchronized void shutdown() {
        try { phaseManager.shutdownScheduler(); } catch (Exception ignore) {}
    }
}
