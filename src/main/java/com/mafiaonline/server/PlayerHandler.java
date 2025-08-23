package com.mafiaonline.server;

import java.io.*;
import java.net.Socket;

public class PlayerHandler extends Thread {
    private final Socket socket;
    private final GameRoom room;
    private PrintWriter out;
    private BufferedReader in;
    private String playerName;
    private Role role = Role.UNASSIGNED;

    // Trạng thái chờ hành động: gõ 1 từ (tên) để thực hiện
    private enum PendingAction { NONE, VOTE, KILL, SAVE, INVESTIGATE, PROTECT }
    private PendingAction pending = PendingAction.NONE;

    public PlayerHandler(Socket socket, GameRoom room) {
        this.socket = socket;
        this.room = room;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            out.println("=== Mafia-Online Server ===");
            out.println("Nhập tên của bạn:");
            String name = in.readLine();
            if (name == null || name.trim().isEmpty()) {
                out.println("Tên không hợp lệ. Đóng kết nối.");
                socket.close();
                return;
            }
            playerName = name.trim();

            room.addPlayer(playerName, this);
            out.println("✅ Bạn đã vào phòng với tên: " + playerName);
            room.broadcast("👤 " + playerName + " đã tham gia phòng.");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("/")) {
                    // ====== LỆNH CÓ DẤU / ======
                    String[] parts = line.split("\\s+", 2);
                    String cmd = parts[0].toLowerCase();
                    String arg = parts.length > 1 ? parts[1].trim() : "";

                    switch (cmd) {
                        case "/start" -> room.startGame();

                        case "/startday", "/day" -> room.startDayPhase();
                        case "/endday" -> room.endDayPhase();

                        case "/startnight", "/night" -> room.startNightPhase();
                        case "/endnight" -> room.endNightPhase();

                        case "/vote" -> {
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /vote <tên>");
                            else room.castVote(playerName, arg);
                            pending = PendingAction.NONE;
                        }

                        case "/kill" -> {
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /kill <tên>");
                            else if (getRole() == Role.MAFIA) room.recordNightAction(playerName, arg);
                            else sendMessage("❌ Chỉ Mafia mới có thể dùng /kill.");
                            pending = PendingAction.NONE;
                        }

                        case "/save" -> {
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /save <tên>");
                            else if (getRole() == Role.DOCTOR) room.recordNightAction(playerName, arg);
                            else sendMessage("❌ Chỉ Doctor mới có thể dùng /save.");
                            pending = PendingAction.NONE;
                        }

                        case "/investigate" -> {
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /investigate <tên>");
                            else if (getRole() == Role.DETECTIVE) room.recordNightAction(playerName, arg);
                            else sendMessage("❌ Chỉ Detective mới có thể dùng /investigate.");
                            pending = PendingAction.NONE;
                        }

                        case "/protect" -> {
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /protect <tên>");
                            else if (getRole() == Role.BODYGUARD) room.recordNightAction(playerName, arg);
                            else sendMessage("❌ Chỉ Bodyguard mới có thể dùng /protect.");
                            pending = PendingAction.NONE;
                        }

                        case "/players" -> sendMessage(
                                "Players:" + room.getPlayersAll().stream()
                                        .map(Player::toString)
                                        .reduce("", (a, b) -> a + "\n" + b)
                        );

                        case "/role" -> sendMessage("🎭 Role: " + getRole());

                        case "/quit" -> {
                            sendMessage("Goodbye.");
                            socket.close();
                            return;
                        }

                        default -> sendMessage("❌ Lệnh không hợp lệ: " + cmd);
                    }
                } else {
                    // ====== INPUT THƯỜNG (KHÔNG /) ======
                    GameState s = room.getState();

                    // Nếu đang chờ action và người chơi gõ 1 token (không có khoảng trắng) => coi là tên mục tiêu
                    if (pending != PendingAction.NONE && !line.contains(" ")) {
                        switch (pending) {
                            case VOTE -> room.castVote(playerName, line);
                            case KILL -> {
                                if (getRole() == Role.MAFIA) room.recordNightAction(playerName, line);
                                else sendMessage("❌ Bạn không phải Mafia.");
                            }
                            case SAVE -> {
                                if (getRole() == Role.DOCTOR) room.recordNightAction(playerName, line);
                                else sendMessage("❌ Bạn không phải Doctor.");
                            }
                            case INVESTIGATE -> {
                                if (getRole() == Role.DETECTIVE) room.recordNightAction(playerName, line);
                                else sendMessage("❌ Bạn không phải Detective.");
                            }
                            case PROTECT -> {
                                if (getRole() == Role.BODYGUARD) room.recordNightAction(playerName, line);
                                else sendMessage("❌ Bạn không phải Bodyguard.");
                            }
                            default -> {}
                        }
                        pending = PendingAction.NONE;
                        continue;
                    }

                    // Không có pending hoặc nhập không phải 1 từ
                    if (s == GameState.NIGHT) {
                        // 🚫 CẤM CHAT BAN ĐÊM
                        sendMessage("🌙 Ban đêm không thể chat. Gõ tên để hành động theo vai trò của bạn.");
                    } else {
                        // Ban ngày: cho phép chat bình thường
                        room.broadcast(playerName + ": " + line);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[PlayerHandler] Lỗi socket cho " + playerName + " : " + e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
            if (playerName != null) {
                room.removePlayer(playerName);
                room.broadcast("❌ " + playerName + " đã ngắt kết nối.");
            }
        }
    }

    public void setRole(Role role) { this.role = role; }

    public Role getRole() {
        if (role != Role.UNASSIGNED) return role;
        Player p = room.getPlayer(playerName);
        return (p != null) ? p.getRole() : Role.UNASSIGNED;
    }

    public void sendMessage(String msg) {
        if (out != null) out.println(msg);
    }

    /** Được gọi khi phase đổi để hiển thị prompt nhập tên theo vai trò/phase */
    public void setPendingForPhase(GameState state) {
        if (!room.isGameStarted() || !isAliveInRoom()) {
            pending = PendingAction.NONE;
            return;
        }
        if (state == GameState.DAY) {
            pending = PendingAction.VOTE;
            sendMessage("🌞 Bạn muốn vote ai? Gõ tên:");
        } else if (state == GameState.NIGHT) {
            switch (getRole()) {
                case MAFIA     -> { pending = PendingAction.KILL;        sendMessage("🌙 Bạn muốn giết ai? Gõ tên:"); }
                case DOCTOR    -> { pending = PendingAction.SAVE;        sendMessage("🌙 Bạn muốn cứu ai? Gõ tên:"); }
                case DETECTIVE -> { pending = PendingAction.INVESTIGATE; sendMessage("🌙 Bạn muốn điều tra ai? Gõ tên:"); }
                case BODYGUARD -> { pending = PendingAction.PROTECT;     sendMessage("🌙 Bạn muốn bảo vệ ai? Gõ tên:"); }
                default -> pending = PendingAction.NONE;
            }
        } else {
            pending = PendingAction.NONE;
        }
    }

    private boolean isAliveInRoom() {
        Player p = room.getPlayer(playerName);
        return p != null && p.isAlive();
    }
}
