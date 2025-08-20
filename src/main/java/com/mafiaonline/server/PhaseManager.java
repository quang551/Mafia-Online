package com.mafiaonline.server;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * PhaseManager with auto-timeout for Day/Night phases.
 *
 * Behavior:
 *  - startDay() / startNight() schedule an automatic end after configured seconds.
 *  - If endDay() / endNight() is called manually, scheduled tasks are cancelled.
 *  - A 10-second warning is broadcast automatically (if phase duration > 10s).
 *
 * Thread-safety: public methods are synchronized.
 */
public class PhaseManager {
    private final GameRoom room;

    // whether currently in night phase (true) or not
    private boolean nightPhase = false;

    // night actions: actorName -> targetName
    private final Map<String, String> nightActions = new HashMap<>();

    // day votes: voterName -> targetName
    private final Map<String, String> dayVotes = new HashMap<>();

    // Scheduler for timeouts
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PhaseManager-Scheduler");
        t.setDaemon(true);
        return t;
    });

    // Scheduled futures (so we can cancel if phase ends early)
    private ScheduledFuture<?> endTask = null;
    private ScheduledFuture<?> warningTask = null;

    // Default durations (seconds) — you can change via constructor or setter
    private int DAY_SECONDS = 60;
    private int NIGHT_SECONDS = 30;

    public PhaseManager(GameRoom room) {
        this.room = room;
    }

    /**
     * Optional: set custom durations (seconds) before starting phases
     */
    public synchronized void setDurations(int daySeconds, int nightSeconds) {
        if (daySeconds > 0) this.DAY_SECONDS = daySeconds;
        if (nightSeconds > 0) this.NIGHT_SECONDS = nightSeconds;
    }

    // -------------------- helper scheduling --------------------
    private synchronized void cancelScheduledTasks() {
        if (endTask != null && !endTask.isDone()) {
            endTask.cancel(false);
            endTask = null;
        }
        if (warningTask != null && !warningTask.isDone()) {
            warningTask.cancel(false);
            warningTask = null;
        }
    }

    private synchronized void scheduleEndTask(Runnable endRunnable, int seconds) {
        cancelScheduledTasks();
        if (seconds <= 0) return;
        // schedule warning if > 10s
        if (seconds > 10) {
            int warnAt = seconds - 10;
            warningTask = scheduler.schedule(() -> {
                try {
                    room.broadcast("⏳ Chỉ còn 10 giây nữa!");
                } catch (Exception ex) {
                    System.err.println("[PhaseManager] warningTask exception: " + ex.getMessage());
                }
            }, warnAt, TimeUnit.SECONDS);
        }
        endTask = scheduler.schedule(() -> {
            try {
                endRunnable.run();
            } catch (Exception ex) {
                System.err.println("[PhaseManager] endTask exception: " + ex.getMessage());
            }
        }, seconds, TimeUnit.SECONDS);
    }

    // -------------------- NIGHT --------------------
    public synchronized void startNight() {
        if (!room.isGameStarted()) {
            room.broadcast("❌ Game chưa bắt đầu.");
            return;
        }
        if (nightPhase) {
            room.broadcast("🌙 Ban đêm đã bắt đầu rồi.");
            return;
        }
        nightPhase = true;
        nightActions.clear();
        room.setState(GameState.NIGHT);

        room.broadcast("🌙 Ban đêm bắt đầu. Các role có hành động ban đêm hãy dùng lệnh tương ứng (/kill /save /investigate /protect).");
        room.broadcast("⏱ Pha Đêm sẽ tự kết thúc sau " + NIGHT_SECONDS + " giây, hoặc dùng /endnight để kết thúc sớm.");

        // schedule automatic endNight()
        scheduleEndTask(this::endNightSafeWrapper, NIGHT_SECONDS);
    }

    /**
     * Safe wrapper so scheduled task calls endNight() via the PhaseManager instance,
     * ensuring synchronized semantics are used.
     */
    private void endNightSafeWrapper() {
        try {
            synchronized (this) { endNight(); }
        } catch (Exception ex) {
            System.err.println("[PhaseManager] endNightSafeWrapper exception: " + ex.getMessage());
        }
    }

    /**
     * Record an action during night: actorName -> targetName
     */
    public synchronized void recordNightAction(String actorNameRaw, String targetNameRaw) {
        String actorName = actorNameRaw == null ? "" : actorNameRaw.trim();
        String targetName = targetNameRaw == null ? "" : targetNameRaw.trim();

        if (!nightPhase) {
            room.sendToPlayer(actorName, "❌ Hiện không phải ban đêm.");
            return;
        }
        if (actorName.isEmpty() || targetName.isEmpty()) {
            room.sendToPlayer(actorName, "❌ Cú pháp không hợp lệ.");
            return;
        }

        Player actor = room.getPlayer(actorName);
        Player target = room.getPlayer(targetName);
        if (actor == null) {
            System.out.println("[PhaseManager] recordNightAction: actor không tồn tại: " + actorName);
            return;
        }
        if (!actor.isAlive()) {
            room.sendToPlayer(actorName, "❌ Bạn đã chết, không thể hành động.");
            return;
        }
        if (target == null || !target.isAlive()) {
            room.sendToPlayer(actorName, "❌ Mục tiêu không tồn tại hoặc đã chết.");
            return;
        }

        nightActions.put(actorName, targetName);
        room.sendToPlayer(actorName, "✅ Ghi nhận hành động ban đêm: " + actorName + " -> " + targetName);
        System.out.println("[PhaseManager] Night action recorded: " + actorName + "(" + actor.getRole() + ") -> " + targetName);
    }

    public synchronized void endNight() {
        // Cancel scheduled tasks because we're ending now (manually or via scheduled)
        cancelScheduledTasks();

        if (!nightPhase) {
            room.broadcast("❌ Chưa phải ban đêm.");
            return;
        }
        nightPhase = false;
        room.setState(GameState.DAY);

        // Collect actions
        String doctorSave = null;
        List<String> detectiveChecks = new ArrayList<>();
        Map<String, String> bodyguardProtects = new HashMap<>();
        Map<String, Integer> mafiaVotes = new HashMap<>();

        for (Map.Entry<String, String> e : nightActions.entrySet()) {
            String actor = e.getKey();
            String target = e.getValue();
            Player actorP = room.getPlayer(actor);
            if (actorP == null || actorP.getRole() == null) continue;
            Role r = actorP.getRole();
            switch (r) {
                case MAFIA -> mafiaVotes.put(target, mafiaVotes.getOrDefault(target, 0) + 1);
                case DOCTOR -> doctorSave = target;
                case DETECTIVE -> detectiveChecks.add(target);
                case BODYGUARD -> bodyguardProtects.put(actor, target);
                default -> { }
            }
        }

        // determine mafia target (majority)
        String mafiaTarget = null;
        if (!mafiaVotes.isEmpty()) {
            int max = mafiaVotes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<String> top = mafiaVotes.entrySet().stream()
                    .filter(en -> en.getValue() == max)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (top.size() == 1) mafiaTarget = top.get(0);
            else room.broadcast("⚠️ Mafia không thống nhất mục tiêu (hoà). Đêm nay không ai bị giết bởi mafia.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🌅 Trời sáng! Kết quả ban đêm:\n");

        // handle bodyguard protection
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

        // resolve kill/save/protect
        if (mafiaTarget != null) {
            if (protectedByBG) {
                Player bg = room.getPlayer(protector);
                if (bg != null && bg.isAlive()) {
                    bg.kill();
                    sb.append("💂 Bodyguard ").append(protector).append(" đã hy sinh để bảo vệ ").append(mafiaTarget).append(".\n");
                } else {
                    if (mafiaTarget.equalsIgnoreCase(doctorSave)) {
                        sb.append("✨ ").append(mafiaTarget).append(" bị tấn công nhưng được Doctor cứu.\n");
                    } else {
                        Player victim = room.getPlayer(mafiaTarget);
                        if (victim != null && victim.isAlive()) {
                            victim.kill();
                            sb.append("💀 ").append(mafiaTarget).append(" đã bị Mafia giết.\n");
                        } else {
                            sb.append("😴 Mafia muốn giết ").append(mafiaTarget).append(" nhưng họ không tồn tại hoặc đã chết.\n");
                        }
                    }
                }
            } else {
                if (mafiaTarget.equalsIgnoreCase(doctorSave)) {
                    sb.append("✨ ").append(mafiaTarget).append(" bị tấn công nhưng được Doctor cứu.\n");
                } else {
                    Player victim = room.getPlayer(mafiaTarget);
                    if (victim != null && victim.isAlive()) {
                        victim.kill();
                        sb.append("💀 ").append(mafiaTarget).append(" đã bị Mafia giết.\n");
                    } else {
                        sb.append("😴 Mafia muốn giết ").append(mafiaTarget).append(" nhưng họ không tồn tại hoặc đã chết.\n");
                    }
                }
            }
        } else {
            if (mafiaVotes.isEmpty()) sb.append("😴 Đêm yên bình, không ai bị giết.\n");
            else sb.append("⚠️ Mafia không thống nhất mục tiêu, không có nạn nhân đêm nay.\n");
        }

        // private detective results
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
            sb.append("🔍 Detective đã điều tra (kết quả gửi riêng).\n");
        }

        room.broadcast(sb.toString());

        // cleanup
        nightActions.clear();

        // after night, check win and start day countdown automatically
        room.checkWinCondition();
        // start day with auto-timeout
        startDay();
    }

    // -------------------- DAY --------------------
    public synchronized void startDay() {
        if (!room.isGameStarted()) {
            room.broadcast("❌ Game chưa bắt đầu.");
            return;
        }
        // cancel any existing scheduled tasks
        cancelScheduledTasks();

        room.setState(GameState.DAY);
        dayVotes.clear();
        room.broadcast("🌞 Ban ngày bắt đầu! Dùng /vote <tên> để vote. Admin dùng /endday để tổng kết.");
        room.broadcast("⏱ Pha Ngày sẽ tự kết thúc sau " + DAY_SECONDS + " giây, hoặc dùng /endday để kết thúc sớm.");

        // schedule automatic endDay()
        scheduleEndTask(this::endDaySafeWrapper, DAY_SECONDS);
    }

    private void endDaySafeWrapper() {
        try {
            synchronized (this) { endDay(); }
        } catch (Exception ex) {
            System.err.println("[PhaseManager] endDaySafeWrapper exception: " + ex.getMessage());
        }
    }

    public synchronized void castVote(String voterRaw, String targetRaw) {
        String voter = voterRaw == null ? "" : voterRaw.trim();
        String target = targetRaw == null ? "" : targetRaw.trim();

        if (room.getState() != GameState.DAY) {
            room.sendToPlayer(voter, "❌ Không thể vote lúc này.");
            return;
        }
        Player v = room.getPlayer(voter);
        Player t = room.getPlayer(target);
        if (v == null || !v.isAlive()) {
            room.sendToPlayer(voter, "❌ Bạn không thể vote (đã chết hoặc không tồn tại).");
            return;
        }
        if (t == null || !t.isAlive()) {
            room.sendToPlayer(voter, "❌ Mục tiêu không tồn tại hoặc đã chết.");
            return;
        }

        dayVotes.put(voter, target);
        room.broadcast("🗳️ " + voter + " đã vote " + target);
    }

    public synchronized void endDay() {
        // Cancel scheduled tasks (because it's ending now)
        cancelScheduledTasks();

        if (room.getState() != GameState.DAY) {
            room.broadcast("❌ Hiện không phải ban ngày.");
            return;
        }

        if (dayVotes.isEmpty()) {
            room.broadcast("🌞 Ngày kết thúc: không ai bị treo cổ.");
            room.setState(GameState.NIGHT);
            startNight();
            return;
        }

        Map<String, Long> counts = dayVotes.values().stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));

        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<String> top = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).collect(Collectors.toList());

        if (top.size() > 1) {
            room.broadcast("⚖️ Vote hòa. Không ai bị treo cổ hôm nay.");
            dayVotes.clear();
            room.setState(GameState.NIGHT);
            startNight();
            return;
        }

        String eliminated = top.get(0);
        Player eliminatedPlayer = room.getPlayer(eliminated);

        if (eliminatedPlayer != null && eliminatedPlayer.isAlive()) {
            if (eliminatedPlayer.getRole() == Role.JESTER) {
                room.broadcast("🤡 " + eliminated + " (Jester) đã bị treo cổ và Jester thắng!");
                room.endGame();
                dayVotes.clear();
                return;
            }

            eliminatedPlayer.kill();
            room.broadcast("🪓 " + eliminated + " đã bị treo cổ bởi dân làng!");
            dayVotes.clear();
            room.checkWinCondition();
        } else {
            room.broadcast("⚠️ Mục tiêu treo cổ không hợp lệ.");
            dayVotes.clear();
        }

        // chuyển sang đêm nếu game vẫn tiếp tục
        if (room.isGameStarted()) {
            room.setState(GameState.NIGHT);
            startNight();
        }
    }

    // Allow external cancellation (for graceful shutdown)
    public synchronized void shutdownScheduler() {
        cancelScheduledTasks();
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {}
    }
}
