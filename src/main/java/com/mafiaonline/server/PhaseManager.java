package com.mafiaonline.server;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class PhaseManager {

    private final GameRoom room;

    // Lưu vote ban ngày: voter -> target
    private final Map<String, String> dayVotes = new HashMap<>();

    // Lưu hành động ban đêm: actor -> target
    private final Map<String, String> nightActions = new HashMap<>();

    // Lập lịch phase (nếu muốn chạy auto)
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> dayTimer;
    private ScheduledFuture<?> nightTimer;

    // Thời lượng phase (giây) – bạn có thể chỉnh dài hơn ở đây
    private int DAY_DURATION_SEC = 300;    // ví dụ 5 phút
    private int NIGHT_DURATION_SEC = 120;  // ví dụ 2 phút

    private boolean nightPhase = false;

    public PhaseManager(GameRoom room) {
        this.room = room;
    }

    /* ==================== DAY PHASE ==================== */

    public synchronized void startDay() {
        cancelScheduledTasks();
        nightPhase = false;
        dayVotes.clear();
        room.setState(GameState.DAY);

        room.broadcast("🌞 Ban ngày bắt đầu. Gõ tên để VOTE (hoặc dùng /vote <tên>).");
        // Hiện prompt “Bạn muốn vote ai?”
        room.promptPendingForPhaseForAll();

        // (tuỳ chọn) tự động kết thúc Day sau thời lượng
        if (DAY_DURATION_SEC > 0) {
            dayTimer = scheduler.schedule(this::safeEndDay, DAY_DURATION_SEC, TimeUnit.SECONDS);
        }
    }

    public synchronized void castVote(String voter, String target) {
        if (room.getState() != GameState.DAY) {
            room.sendToPlayer(voter, "❌ Chưa phải ban ngày.");
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

    public synchronized void endDay() {
        cancelScheduledTasks();
        if (room.getState() != GameState.DAY) {
            room.broadcast("❌ Không ở ban ngày.");
            return;
        }

        if (dayVotes.isEmpty()) {
            room.broadcast("📭 Không có vote nào. Không ai bị treo cổ.");
            startNight();
            return;
        }

        // Tính phiếu: target -> count
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

        // Treo cổ (không tiết lộ vai)
        victim.kill();
        room.broadcast("🪢 " + targetName + " đã bị treo cổ.");

        // ✅ Jester thắng ngay khi bị treo cổ
        if (victim.getRole() == Role.JESTER) {
            room.broadcast("🤡 JESTER THẮNG! " + targetName + " đã đạt mục tiêu khi bị treo cổ.");
            room.endGame();
            return;
        }

        dayVotes.clear();
        startNight();
    }

    private void safeEndDay() {
        try { endDay(); } catch (Exception ignored) {}
    }

    /* ==================== NIGHT PHASE ==================== */

    public synchronized void startNight() {
        cancelScheduledTasks();
        nightPhase = true;
        nightActions.clear();
        room.setState(GameState.NIGHT);

        room.broadcast("🌙 Ban đêm bắt đầu. Gõ tên theo prompt để hành động.");
        // Hiện prompt tùy role: giết/cứu/điều tra/bảo vệ
        room.promptPendingForPhaseForAll();

        if (NIGHT_DURATION_SEC > 0) {
            nightTimer = scheduler.schedule(this::safeEndNight, NIGHT_DURATION_SEC, TimeUnit.SECONDS);
        }
    }

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

        // Ghi nhận (để resolve cuối đêm)
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

        // Gom hành động theo role
        Map<String, Integer> mafiaVotesCount = new HashMap<>(); // target -> count
        String doctorSave = null;                                // target cứu
        Map<String, String> bodyguardProtects = new HashMap<>(); // actor -> target
        List<String> detectiveChecks = new ArrayList<>();        // danh sách người bị điều tra

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

        // Tìm mục tiêu của Mafia (đa số phiếu)
        String mafiaTarget = null;
        if (!mafiaVotesCount.isEmpty()) {
            long max = mafiaVotesCount.values().stream().mapToLong(Integer::longValue).max().orElse(0);
            List<String> top = mafiaVotesCount.entrySet().stream()
                    .filter(x -> x.getValue() == max)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (top.size() == 1) mafiaTarget = top.get(0);
        }

        // Bodyguard: nếu bảo vệ đúng target, BG hy sinh — NHƯNG không tiết lộ
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
                    bg.kill(); // BG hy sinh, nhưng KHÔNG thông báo
                }
                // peaceful
            } else if (mafiaTarget.equalsIgnoreCase(doctorSave)) {
                // peaceful (Doctor cứu nhưng KHÔNG tiết lộ)
            } else {
                Player victim = room.getPlayer(mafiaTarget);
                if (victim != null && victim.isAlive()) {
                    victim.kill();
                    sb.append("💀 ").append(mafiaTarget).append(" đã bị giết.\n");
                    someoneDied = true;
                }
            }
        }

        if (!someoneDied) {
            sb.append("😴 Đêm yên bình, không ai bị giết.\n");
        }

        // Detective: gửi kết quả riêng cho Detective (không broadcast)
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
            // (Không cần ghi gì thêm vào broadcast nếu bạn muốn giữ kín)
        }

        room.broadcast(sb.toString());

        // cleanup
        nightActions.clear();

        // Sang ban ngày
        room.setState(GameState.DAY);
        startDay();
    }

    private void safeEndNight() {
        try { endNight(); } catch (Exception ignored) {}
    }

    /* ==================== UTILS ==================== */

    private boolean isAlive(String name) {
        return room.isAlive(name);
    }

    private void cancelScheduledTasks() {
        try {
            if (dayTimer != null) { dayTimer.cancel(false); dayTimer = null; }
            if (nightTimer != null) { nightTimer.cancel(false); nightTimer = null; }
        } catch (Exception ignored) {}
    }

    /* ==================== Tuỳ chọn API: chỉnh thời lượng ==================== */

    public synchronized void setDayDurationSec(int seconds) {
        this.DAY_DURATION_SEC = Math.max(0, seconds);
    }

    public synchronized void setNightDurationSec(int seconds) {
        this.NIGHT_DURATION_SEC = Math.max(0, seconds);
    }
        /** Gọi khi tắt server để dừng scheduler an toàn */
    public synchronized void shutdownScheduler() {
        cancelScheduledTasks();
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {}
    }
}
