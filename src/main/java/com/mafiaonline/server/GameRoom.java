package com.mafiaonline.server;

import java.util.*;
import java.util.stream.Collectors;

public class GameRoom {
    private final Map<String, Player> players = new LinkedHashMap<>(); // name -> Player
    private final Map<String, PlayerHandler> handlers = new HashMap<>(); // name -> handler
    private final PhaseManager phaseManager;

    private boolean gameStarted = false;
    private GameState state = GameState.LOBBY;

    // Day 4 – lưu hành động ban đêm (mafia/doctor/detective)
    private final Map<String, String> nightActions = new HashMap<>();
    // Day 4 – lưu vote ban ngày
    private final Map<String, String> dayVotes = new HashMap<>();

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

    // ---------- Start game & assign roles (Day 3) ----------
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

        // Assign roles
        List<Role> pool = new ArrayList<>();
        pool.add(Role.MAFIA); // đảm bảo có mafia
        List<Role> extras = Arrays.asList(Role.DOCTOR, Role.DETECTIVE, Role.BODYGUARD, Role.JESTER, Role.VILLAGER);
        int idx = 0;
        while (pool.size() < players.size()) {
            pool.add(extras.get(idx % extras.size()));
            idx++;
        }
        Collections.shuffle(pool);
        Iterator<Role> it = pool.iterator();
        for (Player p : players.values()) {
            Role r = it.next();
            p.setRole(r);
            PlayerHandler h = p.getHandler();
            if (h != null) {
                h.setRole(r);
                h.sendMessage("🎭 Bạn được gán role: " + r + " — " + r.getDescription());
            } else {
                System.out.println("[GameRoom] " + p.getName() + " assigned role " + r);
            }
        }

        broadcast("✅ Trò chơi bắt đầu! Roles đã được phân phối. Hiện là Pha DAY.");
        System.out.println("=== Role assignment ===");
        for (Player p : players.values()) System.out.println(" - " + p.getName() + " -> " + p.getRole());
    }

    // ---------- Day/Night cycle (Day 4) ----------
    public synchronized void startDay() {
        state = GameState.DAY;
        dayVotes.clear();
        broadcast("🌞 Một ngày mới bắt đầu! Mọi người hãy thảo luận và bỏ phiếu.");
    }

    public synchronized void endDay() {
        state = GameState.NIGHT;
        // Tính toán phiếu
        if (!dayVotes.isEmpty()) {
            String target = calculateVoteResult(dayVotes);
            if (target != null) {
                killPlayer(target);
                broadcast("🗳️ Người chơi " + target + " đã bị treo cổ!");
            } else {
                broadcast("🗳️ Không ai bị treo cổ hôm nay.");
            }
        } else {
            broadcast("🗳️ Không ai bỏ phiếu hôm nay.");
        }
        startNight();
    }

    public synchronized void startNight() {
        state = GameState.NIGHT;
        nightActions.clear();
        broadcast("🌙 Đêm xuống! Mafia và các role đặc biệt hãy hành động.");
    }

    public synchronized void endNight() {
        // xử lý nightActions
        if (!nightActions.isEmpty()) {
            resolveNightActions();
        } else {
            broadcast("🌙 Đêm trôi qua yên bình, không có ai chết.");
        }
        startDay();
    }

    // ---------- Record actions (Day 4) ----------
    public synchronized void recordNightAction(String actor, String target) {
        if (!players.containsKey(actor) || !players.containsKey(target)) return;
        if (!players.get(actor).isAlive()) return;
        nightActions.put(actor, target);
        System.out.println("[GameRoom] " + actor + " chọn " + target + " ban đêm.");
    }

    public synchronized void castVote(String voter, String target) {
        if (!players.containsKey(voter) || !players.containsKey(target)) return;
        if (!players.get(voter).isAlive()) return;
        dayVotes.put(voter, target);
        broadcast("🗳️ " + voter + " đã vote treo " + target + ".");
    }

    // ---------- Kill & Win condition ----------
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

    // ---------- Helpers ----------
    private String calculateVoteResult(Map<String, String> votes) {
        Map<String, Long> counts = votes.values().stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
    }

    private void resolveNightActions() {
        // xử lý đơn giản: mafia giết, doctor cứu
        Set<String> mafiaTargets = new HashSet<>();
        Set<String> saved = new HashSet<>();

        for (Map.Entry<String, String> e : nightActions.entrySet()) {
            Player actor = players.get(e.getKey());
            String target = e.getValue();
            if (actor.getRole() == Role.MAFIA) {
                mafiaTargets.add(target);
            } else if (actor.getRole() == Role.DOCTOR) {
                saved.add(target);
            }
        }

        for (String t : mafiaTargets) {
            if (!saved.contains(t)) {
                killPlayer(t);
                broadcast("🌙 Ban đêm, " + t + " đã bị giết!");
            } else {
                broadcast("🌙 " + t + " đã bị tấn công nhưng được cứu!");
            }
        }
    }

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
