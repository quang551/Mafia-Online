package com.mafiaonline.server;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GameRoom
 * - Quản lý người chơi, vai, trạng thái; phối hợp với PhaseManager
 * - UI hooks: "PHASE: ...", "PLAYERS: ...", "DEAD: name", "RESET_ROLES"
 * - Luật thắng:
 *      Dân thắng khi không còn Mafia
 *      Mafia thắng khi MA ≥ Others  <=>  2*MA ≥ TotalAlive
 * - An toàn: luôn gọi checkWinCondition() sau mọi thay đổi nhân sự / chuyển pha
 */
public class GameRoom {

    /* ==================== Trạng thái ==================== */

    private final Map<String, Player> players   = new LinkedHashMap<>(); // name -> Player
    private final Map<String, PlayerHandler> handlers = new HashMap<>(); // name -> handler
    private final PhaseManager phaseManager;

    private boolean gameStarted = false;
    private GameState state = GameState.LOBBY;

    public GameRoom() {
        this.phaseManager = new PhaseManager(this);
    }

    /* ==================== Getters cơ bản ==================== */

    public synchronized boolean isGameStarted() { return gameStarted; }
    public synchronized GameState getState()    { return state; }
    public PhaseManager getPhaseManager()       { return phaseManager; }

    public synchronized Player getPlayer(String name) { return players.get(name); }
    public synchronized Collection<Player> getPlayersAll() {
        return Collections.unmodifiableCollection(players.values());
    }
    public synchronized List<Player> getPlayersAlive() {
        return players.values().stream().filter(Player::isAlive).collect(Collectors.toList());
    }

    /* ==================== Quản lý người chơi ==================== */

    public synchronized void addPlayer(String name) {
        if (players.containsKey(name)) {
            System.out.println("[GameRoom] Tên '" + name + "' đã tồn tại.");
            return;
        }
        Player p = new Player(name);
        players.put(name, p);
        System.out.println("[GameRoom] Player added: " + p);
        broadcastPlayersAlive(); // UI cập nhật ngay
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
        broadcastPlayersAlive(); // UI cập nhật ngay
    }

    public synchronized void removePlayer(String name) {
        Player removed = players.remove(name);
        handlers.remove(name);
        if (removed != null) {
            System.out.println("[GameRoom] Player removed: " + removed.getName());
            broadcast("📤 Người chơi " + name + " đã rời phòng.");
            broadcastPlayersAlive(); // UI
            checkWinCondition();     // quan trọng nếu tỉ lệ MA/OTH thay đổi
        }
    }

    /* ==================== Phase & State ==================== */

    /** Set state + phát "PHASE: ..." + prompt pending theo phase. */
    public synchronized void setState(GameState newState) {
        this.state = newState;
        System.out.println("[GameRoom] State -> " + newState);
        broadcastPhase(newState);
        promptPendingForPhaseForAll();
    }

    /* ==================== Bắt đầu ván & chia vai ==================== */

    public synchronized void startGame() {
        if (gameStarted) {
            broadcast("⚠️ Game đã bắt đầu.");
            return;
        }
        if (players.size() < 3) {
            broadcast("❌ Cần ít nhất 3 người để bắt đầu game (hiện: " + players.size() + ").");
            return;
        }

        // Cho UI quên vai cũ để tránh leak khi rematch
        broadcast("RESET_ROLES");

        // Reset trạng thái người chơi trước khi random
        for (Player p : players.values()) {
            p.setAlive(true);
            p.setRole(Role.UNASSIGNED);
        }
        broadcastPlayersAlive();

        gameStarted = true;
        setState(GameState.DAY); // phát "PHASE: DAY" + prompt

        // Xây pool role theo số người
        final int playerCount = players.size();
        List<Role> pool = new ArrayList<>(playerCount);

        // Số mafia: 1 (<7), 2 (7–8), 3 (>=9)
        int mafiaCount = (playerCount >= 9) ? 3 : (playerCount >= 7 ? 2 : 1);
        for (int i = 0; i < mafiaCount; i++) pool.add(Role.MAFIA);

        // Thêm các vai đặc biệt tối đa 1 mỗi loại (nếu còn slot)
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
                // hai dòng này để client bắt được role cá nhân (không lộ người khác)
                h.sendMessage("🎭 Role của bạn: " + r + " — " + r.getDescription());
                h.sendMessage("[ROLE_SELF] " + r.name());
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

        broadcastPlayersAlive();   // UI: danh sách người sống để vote
        phaseManager.startDay();   // Ngày: CHAT -> VOTE -> RESOLVE (PhaseManager điều phối)
    }

    /* ==================== Wrappers tới PhaseManager ==================== */

    public synchronized void startDayPhase() {
        setState(GameState.DAY);
        phaseManager.startDay();
    }

    public synchronized void openVotePhase() {
        phaseManager.openVotePhase();
    }

    public synchronized void resolveDayPhase() {
        phaseManager.resolveDay();
        checkWinCondition(); // sau lynch/vote nên check ngay
    }

    /** Tương thích cũ: kết thúc NGÀY. */
    public synchronized void endDayPhase() {
        phaseManager.endDay();
        checkWinCondition();
    }

    public synchronized void castVote(String voter, String target) {
        phaseManager.castVote(voter, target);
    }

    public synchronized void startNightPhase() {
        setState(GameState.NIGHT);
        phaseManager.startNight();
    }

    /** Kết thúc ĐÊM: áp dụng kill/save/protect xong thì check win. */
    public synchronized void endNightPhase() {
        phaseManager.endNight();
        checkWinCondition();
    }

    public synchronized void recordNightAction(String actor, String target) {
        phaseManager.recordNightAction(actor, target);
    }

    /** Cho PlayerHandler kiểm tra để “vote bằng cách gõ tên” */
    public synchronized boolean isVotingOpen() {
        return phaseManager != null && phaseManager.isVotingOpen();
    }

    /* ==================== Kill & Win ==================== */

    /** Luôn dùng hàm này thay vì tự setAlive(false) ở nơi khác. */
    public synchronized void killPlayer(String name) {
        Player p = players.get(name);
        if (p != null && p.isAlive()) {
            p.kill();
            PlayerHandler h = p.getHandler();
            if (h != null) h.sendMessage("☠️ Bạn đã chết!");
            broadcast("💀 " + name + " đã bị loại khỏi game.");
            broadcast("DEAD: " + name);   // UI hook: đánh dấu chết
            broadcastPlayersAlive();      // UI hook: cập nhật danh sách
            checkWinCondition();
        }
    }

    /**
     * Kiểm tra thắng/thua sau mọi thay đổi nhân sự.
     * - Dân thắng: KHÔNG còn Mafia
     * - Mafia thắng: Mafia ≥ người còn lại  <=>  2*mafiaAlive ≥ totalAlive
     */
    public synchronized void checkWinCondition() {
        if (!gameStarted) return;

        long mafiaAlive = players.values().stream()
                .filter(p -> p.isAlive() && p.getRole() == Role.MAFIA)
                .count();

        long totalAlive = players.values().stream()
                .filter(Player::isAlive)
                .count();

        System.out.println("[WinCheck] mafiaAlive=" + mafiaAlive + ", totalAlive=" + totalAlive);

        if (mafiaAlive == 0) {
            broadcast("🎉 DÂN LÀNG THẮNG! Tất cả Mafia đã bị loại.");
            endGame();
            return;
        }
        // ví dụ còn 2 người (1 MA + 1 khác): 2*1 >= 2 -> Mafia thắng
        if (mafiaAlive * 2 >= totalAlive) {
            broadcast("😈 MAFIA THẮNG! Số Mafia đã ≥ số người còn lại.");
            endGame();
        }
    }

    /**
     * Kết thúc game: dừng pha, lộ role, reset về lobby sạch (UI: PHASE: END -> LOBBY).
     * Nếu PhaseManager có hàm resetForNewGameLobby() thì gọi qua reflection (không bắt buộc).
     */
    public synchronized void endGame() {
        this.gameStarted = false;

        // Dừng/Reset phase timers nếu PhaseManager có API này
        try {
            PhaseManager.class.getMethod("resetForNewGameLobby").invoke(phaseManager);
        } catch (Exception ignore) {
            // nếu không có method, vẫn tiếp tục; đảm bảo timer đã cancel ở endDay/endNight/kill
        }

        setState(GameState.END); // phát "PHASE: END"

        // Lộ role khi kết thúc
        String reveal = players.values().stream()
                .map(p -> p.getName() + " → " + p.getRole())
                .collect(Collectors.joining(", "));
        broadcast("🏁 Trò chơi kết thúc. Vai: " + reveal);

        // Reset về lobby
        for (Player p : players.values()) {
            p.setRole(Role.UNASSIGNED);
            p.setAlive(true);
        }
        broadcastPlayersAlive(); // UI
        setState(GameState.LOBBY);

        // Gợi ý rematch
        broadcast("🔁 Ván mới: host gõ /start hoặc bấm nút 'Bắt đầu ván mới'.");
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

    /* ==================== Helpers ==================== */

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

    /** Lấy handler theo tên */
    public synchronized PlayerHandler getHandler(String name) { return handlers.get(name); }

    /** Lấy tất cả handler */
    public synchronized Collection<PlayerHandler> getAllHandlers() {
        return Collections.unmodifiableCollection(handlers.values());
    }

    /** Prompt pending cho tất cả player theo phase hiện tại */
    public synchronized void promptPendingForPhaseForAll() {
        GameState s = getState();
        for (PlayerHandler h : handlers.values()) {
            h.setPendingForPhase(s);
        }
    }

    /** Dừng scheduler an toàn khi tắt server */
    public synchronized void shutdown() {
        try { phaseManager.shutdownScheduler(); } catch (Exception ignore) {}
    }

    /* ==================== UI hooks ==================== */

    /** Phát "PLAYERS: a, b, c" (alive) để UI xây list + vote */
    private void broadcastPlayersAlive() {
        String csv = players.values().stream()
                .filter(Player::isAlive)
                .map(Player::getName)
                .sorted(String::compareToIgnoreCase)
                .collect(Collectors.joining(", "));
        broadcast("PLAYERS: " + csv);
    }

    /** Phát "PHASE: ..." cho UI */
    private void broadcastPhase(GameState st) {
        String phase = switch (st) {
            case DAY   -> "DAY";
            case NIGHT -> "NIGHT";
            case LOBBY -> "LOBBY";
            case END   -> "END";
        };
        broadcast("PHASE: " + phase);
    }
}
