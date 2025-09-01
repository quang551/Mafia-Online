package com.mafiaonline.server;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Quản lý pha chơi:
 *  - Ban ngày: CHAT (thảo luận) -> VOTE (bầu) -> RESOLVE (chốt)
 *  - Ban đêm: thu hành động role, resolve và sang ngày mới
 *
 * Gợi ý UI: client có thể bắt các chuỗi
 *   [DAY][CHAT] <sec>
 *   [DAY][VOTE] <sec>
 *   [DAY] End of day
 *   [NIGHT] <sec>
 * để hiển thị phase + đồng hồ.
 *
 * Có thể override thời lượng bằng VM options khi chạy server:
 *   -Dday.chat.seconds=60 -Dday.vote.seconds=90 -Dnight.seconds=120
 */
public class PhaseManager {

    private final GameRoom room;

    /* ====== DAY subphase ====== */
    private enum DaySubPhase { CHAT, VOTE, RESOLVE }
    private DaySubPhase daySubPhase = DaySubPhase.CHAT;

    /* ====== State ====== */
    // Vote ban ngày: voter -> target
    private final Map<String, String> dayVotes = new HashMap<>();
    // Hành động ban đêm: actor -> target
    private final Map<String, String> nightActions = new HashMap<>();

    /* ====== Scheduler ====== */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "phase-timer");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> chatTimer;
    private ScheduledFuture<?> voteTimer;
    private ScheduledFuture<?> nightTimer;

    /* ====== Durations (seconds) ====== */
    private volatile int CHAT_DURATION_SEC  = Integer.getInteger("day.chat.seconds", 60);
    private volatile int VOTE_DURATION_SEC  = Integer.getInteger("day.vote.seconds", 90);
    private volatile int NIGHT_DURATION_SEC = Integer.getInteger("night.seconds",     120);

    public PhaseManager(GameRoom room) {
        this.room = room;
    }

    /* ==================== DAY ==================== */

    /** Bắt đầu ban ngày với subphase CHAT. */
    public synchronized void startDay() {
        cancelScheduledTasks();
        dayVotes.clear();
        daySubPhase = DaySubPhase.CHAT;

        room.setState(GameState.DAY);
        room.broadcast("🌞 Ban ngày bắt đầu. Giai đoạn CHAT để thảo luận.");
        room.broadcast("[DAY][CHAT] " + CHAT_DURATION_SEC + " giây");
        room.promptPendingForPhaseForAll();

        if (CHAT_DURATION_SEC > 0) {
            chatTimer = scheduler.schedule(this::safeOpenVotePhase, CHAT_DURATION_SEC, TimeUnit.SECONDS);
        }
    }

    /** Mở giai đoạn VOTE (sau CHAT). Có thể gọi thủ công (admin). */
    public synchronized void openVotePhase() {
        if (room.getState() != GameState.DAY || daySubPhase != DaySubPhase.CHAT) return;

        daySubPhase = DaySubPhase.VOTE;
        room.broadcast("[DAY][VOTE] " + VOTE_DURATION_SEC + " giây");
        room.broadcast("🗳️ Giai đoạn VOTE mở: dùng /vote <username>");

        if (VOTE_DURATION_SEC > 0) {
            voteTimer = scheduler.schedule(this::safeResolveDay, VOTE_DURATION_SEC, TimeUnit.SECONDS);
        }
    }

    /** Cho phép chỗ khác kiểm tra có đang mở VOTE không (để chặn /vote khi đang CHAT). */
    public synchronized boolean isVotingOpen() {
        return room.getState() == GameState.DAY && daySubPhase == DaySubPhase.VOTE;
    }

    /** /vote voter -> target. */
    public synchronized void castVote(String voter, String target) {
        if (room.getState() != GameState.DAY) {
            room.sendToPlayer(voter, "❌ Chưa phải ban ngày.");
            return;
        }
        if (daySubPhase != DaySubPhase.VOTE) {
            room.sendToPlayer(voter, "⏳ Chưa tới giờ vote. Hãy chờ hết giai đoạn CHAT.");
            return;
        }
        if (!isAlive(voter)) {
            room.sendToPlayer(voter, "❌ Bạn đã chết, không thể vote.");
            return;
        }
        if (!isAlive(target)) {
            room.sendToPlayer(voter, "❌ Mục tiêu không tồn tại hoặc đã chết.");
            return;
        }

        dayVotes.put(voter, target);
        room.broadcast("🗳️ " + voter + " đã vote " + target + ".");
    }

    /** Hết VOTE → chốt phiếu, xử tử nếu có, sang đêm. */
    public synchronized void resolveDay() {
        cancelScheduledTasks(); // tránh timer cũ bắn muộn

        if (room.getState() != GameState.DAY) {
            room.broadcast("❌ Không ở ban ngày.");
            return;
        }
        daySubPhase = DaySubPhase.RESOLVE;

        room.broadcast("[DAY] End of day");

        if (dayVotes.isEmpty()) {
            room.broadcast("📭 Không có vote nào. Không ai bị treo cổ.");
            dayVotes.clear();
            startNight();
            return;
        }

        // Đếm phiếu: target -> count
        Map<String, Long> counts = dayVotes.values().stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));

        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<String> top = counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (top.size() > 1) {
            room.broadcast("⚖️ Vote hòa. Không ai bị treo cổ hôm nay.");
            dayVotes.clear();
            startNight();
            return;
        }

        String targetName = top.get(0);
        Player victim = room.getPlayer(targetName);

        if (victim == null || !victim.isAlive()) {
            room.broadcast("⚠️ Người bị vote treo cổ không hợp lệ hoặc đã chết. Không ai bị treo cổ.");
            dayVotes.clear();
            startNight();
            return;
        }

        // Treo cổ (không tiết lộ vai) — dùng API GameRoom để UI cập nhật DEAD & kiểm tra thắng
        room.killPlayer(targetName);

        // Jester thắng ngay khi bị treo cổ
        if (victim.getRole() == Role.JESTER) {
            room.broadcast("🤡 JESTER THẮNG! " + targetName + " đã đạt mục tiêu khi bị treo cổ.");
            room.endGame();
            dayVotes.clear();
            return;
        }

        dayVotes.clear();
        // Nếu game chưa kết thúc, sang đêm
        if (room.isGameStarted()) {
            startNight();
        }
    }

    /** Giữ tương thích cũ: gọi endDay() sẽ chốt phiếu và sang đêm. */
    public synchronized void endDay() {
        cancelScheduledTasks();
        resolveDay();
    }

    private void safeOpenVotePhase() { try { openVotePhase(); } catch (Exception ignored) {} }
    private void safeResolveDay()    { try { resolveDay();    } catch (Exception ignored) {} }

    /* ==================== NIGHT ==================== */

    public synchronized void startNight() {
        cancelScheduledTasks();
        nightActions.clear();
        room.setState(GameState.NIGHT);

        room.broadcast("🌙 Ban đêm bắt đầu. Gõ theo prompt để hành động.");
        room.broadcast("[NIGHT] " + NIGHT_DURATION_SEC + " giây");
        room.promptPendingForPhaseForAll();

        if (NIGHT_DURATION_SEC > 0) {
            nightTimer = scheduler.schedule(this::safeEndNight, NIGHT_DURATION_SEC, TimeUnit.SECONDS);
        }
    }

    /** Ghi nhận hành động đêm (role-based), resolve ở endNight(). */
    public synchronized void recordNightAction(String actorName, String targetName) {
        if (room.getState() != GameState.NIGHT) {
            room.sendToPlayer(actorName, "❌ Chưa phải ban đêm.");
            return;
        }
        if (!isAlive(actorName)) {
            room.sendToPlayer(actorName, "❌ Bạn đã chết, không thể hành động.");
            return;
        }
        if (!isAlive(targetName)) {
            room.sendToPlayer(actorName, "❌ Mục tiêu không tồn tại hoặc đã chết.");
            return;
        }

        Player actor = room.getPlayer(actorName);
        if (actor == null || actor.getRole() == null) {
            room.sendToPlayer(actorName, "❌ Không xác định được vai của bạn.");
            return;
        }

        nightActions.put(actorName, targetName);
        room.sendToPlayer(actorName, "✅ Đã ghi nhận hành động đêm: " + actorName + " -> " + targetName);
        System.out.println("[PhaseManager] Night action: " + actorName + "(" + actor.getRole() + ") -> " + targetName);
    }

    public synchronized void endNight() {
        cancelScheduledTasks();
        if (room.getState() != GameState.NIGHT) {
            room.broadcast("❌ Không ở ban đêm.");
            return;
        }

        // Gom hành động
        Map<String, Integer> mafiaVotesCount = new HashMap<>(); // target -> count
        String doctorSave = null;                                // target cứu
        Map<String, String> bodyguardProtects = new HashMap<>(); // actor -> target
        List<String> detectiveChecks = new ArrayList<>();        // các mục tiêu bị điều tra

        for (Map.Entry<String, String> e : nightActions.entrySet()) {
            String actor = e.getKey();
            String target = e.getValue();
            Player p = room.getPlayer(actor);
            if (p == null || !p.isAlive()) continue;

            Role r = p.getRole();
            if (r == null) continue;

            switch (r) {
                case MAFIA     -> mafiaVotesCount.merge(target, 1, Integer::sum);
                case DOCTOR    -> doctorSave = target;
                case BODYGUARD -> bodyguardProtects.put(actor, target);
                case DETECTIVE -> detectiveChecks.add(target);
                default -> {}
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🌅 Trời sáng! Kết quả ban đêm:\n");

        // Mục tiêu mafia (đa số)
        String mafiaTarget = null;
        if (!mafiaVotesCount.isEmpty()) {
            long max = mafiaVotesCount.values().stream().mapToLong(Integer::longValue).max().orElse(0);
            List<String> top = mafiaVotesCount.entrySet().stream()
                    .filter(x -> x.getValue() == max)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (top.size() == 1) mafiaTarget = top.get(0);
        }

        // Bodyguard che chắn -> hy sinh (không công bố)
        boolean protectedByBG = false;
        String protector = null;
        if (mafiaTarget != null) {
            for (Map.Entry<String, String> e : bodyguardProtects.entrySet()) {
                if (e.getValue().equalsIgnoreCase(mafiaTarget)) {
                    protectedByBG = true;
                    protector = e.getKey();
                    break;
                }
            }
        }

        // Resolve kill/save/protect — KHÔNG TIẾT LỘ cơ chế
        boolean someoneDied = false;
        if (mafiaTarget != null) {
            if (protectedByBG) {
                Player bg = room.getPlayer(protector);
                if (bg != null && bg.isAlive()) {
                    // BG hy sinh, KHÔNG broadcast chi tiết; (UI sẽ không biết — chủ đích "ẩn")
                    bg.kill();
                    someoneDied = true; // có người chết nhưng không nêu tên
                }
            } else if (mafiaTarget.equalsIgnoreCase(doctorSave)) {
                // Doctor cứu — không công bố
            } else {
                // Giết nạn nhân, dùng API GameRoom để UI thấy DEAD và để check win
                room.killPlayer(mafiaTarget);
                sb.append("💀 ").append(mafiaTarget).append(" đã bị giết.\n");
                someoneDied = true;
            }
        }

        if (!someoneDied) sb.append("😴 Đêm yên bình, không ai bị giết.\n");

        // Detective: trả kết quả riêng
        if (!detectiveChecks.isEmpty()) {
            for (String check : detectiveChecks) {
                Player target = room.getPlayer(check);
                String roleName = (target != null && target.getRole() != null) ? target.getRole().name() : "UNKNOWN";
                for (Player p : room.getPlayersAlive()) {
                    if (p.getRole() == Role.DETECTIVE) {
                        room.sendToPlayer(p.getName(), "🔍 Điều tra: " + check + " là " + roleName);
                    }
                }
            }
        }

        room.broadcast(sb.toString());

        nightActions.clear();

        // Kiểm tra thắng — nếu game kết thúc thì dừng; nếu chưa, sang ngày mới
        room.checkWinCondition();
        if (room.isGameStarted()) {
            startDay();
        }
    }

    private void safeEndNight() { try { endNight(); } catch (Exception ignored) {} }

    /* ==================== UTILS ==================== */

    private boolean isAlive(String name) {
        return room.isAlive(name);
    }

    private void cancelScheduledTasks() {
        try {
            if (chatTimer  != null) { chatTimer.cancel(false);  chatTimer  = null; }
            if (voteTimer  != null) { voteTimer.cancel(false);  voteTimer  = null; }
            if (nightTimer != null) { nightTimer.cancel(false); nightTimer = null; }
        } catch (Exception ignored) {}
    }

    /* ==================== CONFIG API ==================== */

    /** Đổi thời gian CHAT (giây). */
    public synchronized void setChatDurationSec(int seconds) {
        this.CHAT_DURATION_SEC = Math.max(0, seconds);
    }

    /** Đổi thời gian VOTE (giây). */
    public synchronized void setVoteDurationSec(int seconds) {
        this.VOTE_DURATION_SEC = Math.max(0, seconds);
    }

    /** Đổi thời gian NIGHT (giây). */
    public synchronized void setNightDurationSec(int seconds) {
        this.NIGHT_DURATION_SEC = Math.max(0, seconds);
    }

    /** Tương thích cũ: setDayDurationSec() = đổi phần CHAT. */
    public synchronized void setDayDurationSec(int seconds) {
        setChatDurationSec(seconds);
    }

    /** Gọi khi tắt server để dừng scheduler an toàn. */
    public synchronized void shutdownScheduler() {
        cancelScheduledTasks();
        try { scheduler.shutdownNow(); } catch (Exception ignored) {}
    }
}
