package com.mafiaonline.server;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PhaseManager - night/day handling (cải thiện từ bản trước)
 *
 * Thay đổi:
 * - validate tên (trim, không rỗng)
 * - chọn mafiaTarget bằng cách đếm phiếu mafia (nếu nhiều mafia)
 * - xử lý target không tồn tại / đã chết
 * - gửi kết quả detective chỉ cho detective còn sống
 * - thông báo rõ ràng cho actor khi hành động không hợp lệ
 */
public class PhaseManager {
    private final GameRoom room;

    private boolean nightPhase = false;

    // night actions: actorName -> targetName
    private final Map<String, String> nightActions = new HashMap<>();

    // day votes: voterName -> targetName
    private final Map<String, String> votes = new HashMap<>();

    public PhaseManager(GameRoom room) {
        this.room = room;
    }

    // ---------- Night ----------
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
        room.broadcast("🌙 Ban đêm bắt đầu. Những role có hành động ban đêm, dùng lệnh tương ứng.");
    }

    /**
     * Ghi nhận hành động ban đêm.
     * actorName/targetName được trim, kiểm tra tồn tại.
     */
    public synchronized void recordNightAction(String actorNameRaw, String targetNameRaw) {
        String actorName = actorNameRaw == null ? "" : actorNameRaw.trim();
        String targetName = targetNameRaw == null ? "" : targetNameRaw.trim();

        if (!nightPhase) {
            room.sendToPlayer(actorName, "❌ Hiện không phải ban đêm.");
            return;
        }
        if (actorName.isEmpty() || targetName.isEmpty()) {
            room.sendToPlayer(actorName, "❌ Cú pháp không hợp lệ. Vui lòng dùng tên mục tiêu hợp lệ.");
            return;
        }

        Player actor = room.getPlayer(actorName);
        if (actor == null) {
            // actor không tồn tại (lỗi client)
            System.out.println("[PhaseManager] recordNightAction: actor không tồn tại: " + actorName);
            return;
        }
        if (!actor.isAlive()) {
            room.sendToPlayer(actorName, "❌ Bạn đã chết, không thể hành động.");
            return;
        }

        Player target = room.getPlayer(targetName);
        if (target == null) {
            room.sendToPlayer(actorName, "❌ Mục tiêu '" + targetName + "' không tồn tại.");
            return;
        }
        // allow actions on alive only (doctor could 'save' dead? disallow)
        if (!target.isAlive()) {
            room.sendToPlayer(actorName, "❌ Mục tiêu '" + targetName + "' đã chết.");
            return;
        }

        Role r = actor.getRole();
        if (r == null) {
            room.sendToPlayer(actorName, "❌ Bạn chưa được phân role, không thể hành động.");
            return;
        }

        // store action (one action per actor; override previous if actor re-acts)
        nightActions.put(actorName, targetName);
        room.sendToPlayer(actorName, "✅ Ghi nhận hành động ban đêm: " + actorName + " -> " + targetName);
        System.out.println("[PhaseManager] Night action recorded: " + actorName + "(" + r + ") -> " + targetName);
    }

    /**
     * Xử lý kết quả ban đêm:
     * - chọn mafiaTarget bằng majority vote của các actor có role MAFIA
     * - xử lý doctor save, bodyguard protect, detective investigate
     */
    public synchronized void endNight() {
        if (!nightPhase) {
            room.broadcast("❌ Chưa phải ban đêm.");
            return;
        }
        nightPhase = false;
        room.setState(GameState.DAY);

        // collect actions grouped by role
        String doctorSave = null;
        List<String> detectiveChecks = new ArrayList<>();
        Map<String, String> bodyguardProtects = new HashMap<>(); // protector -> protected

        // mafia votes: targetName -> count
        Map<String, Integer> mafiaVotes = new HashMap<>();

        for (Map.Entry<String, String> e : nightActions.entrySet()) {
            String actor = e.getKey();
            String target = e.getValue();
            Player actorP = room.getPlayer(actor);
            if (actorP == null) continue;
            Role role = actorP.getRole();
            if (role == null) continue;

            switch (role) {
                case MAFIA -> {
                    mafiaVotes.put(target, mafiaVotes.getOrDefault(target, 0) + 1);
                }
                case DOCTOR -> doctorSave = target;
                case DETECTIVE -> detectiveChecks.add(target);
                case BODYGUARD -> bodyguardProtects.put(actor, target);
                default -> {
                    // other roles have no night action
                }
            }
        }

        // determine mafiaTarget by max votes (tie -> mafia not agree -> no kill)
        String mafiaTarget = null;
        if (!mafiaVotes.isEmpty()) {
            int max = mafiaVotes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<String> top = mafiaVotes.entrySet().stream()
                    .filter(en -> en.getValue() == max)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (top.size() == 1) {
                mafiaTarget = top.get(0);
            } else {
                // tie among mafia => no kill (or you could pick random)
                room.broadcast("⚠️ Mafia không thống nhất mục tiêu (hoà). Đêm nay không ai bị giết bởi mafia.");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🌅 Trời sáng! Kết quả ban đêm:\n");

        // handle bodyguard protection (if any bodyguard protected mafiaTarget)
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
                    // mafiaTarget survives
                } else {
                    // protector dead -> fallback to normal handling
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
            // mafiaTarget null can mean no mafia actions OR mafia tie
            if (mafiaVotes.isEmpty()) {
                sb.append("😴 Đêm yên bình, không ai bị giết.\n");
            } else {
                // mafia tie already broadcast earlier; show note
                sb.append("⚠️ Mafia không thống nhất mục tiêu, không có nạn nhân bị giết bởi mafia đêm nay.\n");
            }
        }

        // detective results: send privately to alive detectives
        if (!detectiveChecks.isEmpty()) {
            for (String investigated : detectiveChecks) {
                Player target = room.getPlayer(investigated);
                String roleName = (target != null && target.getRole() != null) ? target.getRole().name() : "UNKNOWN";
                for (Player p : room.getPlayersAlive()) {
                    if (p.getRole() == Role.DETECTIVE) {
                        room.sendToPlayer(p.getName(), "🔍 Kết quả điều tra: " + investigated + " là " + roleName);
                    }
                }
            }
            sb.append("🔍 Detective đã điều tra (kết quả gửi riêng).\n");
        }

        room.broadcast(sb.toString());

        // cleanup
        nightActions.clear();

        // after night processing, check win condition
        room.checkWinCondition();

        room.broadcast("➡️ Hiện tại là ban ngày. Thảo luận và dùng /vote <tên> để vote. Dùng /endday để kết thúc ngày.");
    }

    // ---------- Day (vote) ----------
    public synchronized void castVote(String voterRaw, String targetRaw) {
        String voter = voterRaw == null ? "" : voterRaw.trim();
        String target = targetRaw == null ? "" : targetRaw.trim();

        if (nightPhase) {
            room.sendToPlayer(voter, "❌ Không thể vote ban đêm.");
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
        votes.put(voter, target);
        room.broadcast("🗳️ " + voter + " đã vote " + target);
    }

    public synchronized void endDay() {
        if (nightPhase) {
            room.broadcast("❌ Hiện là ban đêm, không thể end day.");
            return;
        }
        if (votes.isEmpty()) {
            room.broadcast("🌞 Ngày kết thúc: không ai bị treo cổ.");
            return;
        }

        Map<String, Long> counts = votes.values().stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));

        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<String> top = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).collect(Collectors.toList());

        if (top.size() > 1) {
            room.broadcast("⚖️ Vote hoà. Không ai bị treo cổ hôm nay.");
            votes.clear();
            return;
        }

        String eliminated = top.get(0);
        Player eliminatedPlayer = room.getPlayer(eliminated);

        if (eliminatedPlayer != null && eliminatedPlayer.isAlive()) {
            // Jester special: if eliminated, Jester wins
            if (eliminatedPlayer.getRole() == Role.JESTER) {
                room.broadcast("🤡 " + eliminated + " (Jester) đã bị treo cổ và Jester thắng!");
                room.endGame();
                votes.clear();
                return;
            }

            eliminatedPlayer.kill();
            room.broadcast("🪓 " + eliminated + " đã bị treo cổ bởi dân làng!");
            votes.clear();

            room.checkWinCondition();
        } else {
            room.broadcast("⚠️ Mục tiêu treo cổ không hợp lệ.");
            votes.clear();
        }
    }

    public synchronized void resetDayVotes() {
        votes.clear();
    }
}
