package com.mafiaonline.server;

import com.mafiaonline.server.auth.AuthService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PlayerHandler (TCP) — mỗi client một thread.
 * Bản dành cho chế độ Bridge (WS<->TCP): KHÔNG phụ thuộc WsIntegratedServer.
 * Để web UI cập nhật, handler sẽ broadcast một số dòng định dạng:
 *   - "PLAYERS: name1, name2, ..."
 *   - "PHASE: DAY START|DAY END|NIGHT START|NIGHT END"
 */
public class PlayerHandler extends Thread {
    // ===== Auth =====
    private static final AuthService AUTH = new AuthService();
    private volatile boolean authenticated = false;
    private String username = null;   // username sau khi login

    // ===== Networking =====
    private final Socket socket;
    private final GameRoom room;
    private PrintWriter out;
    private BufferedReader in;

    // ===== Game state =====
    private String playerName;        // trùng username sau khi login
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
            // Dùng UTF-8 để thống nhất với client
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // Chào mừng + hướng dẫn auth
            out.println("=== Mafia-Online Server ===");
            out.println("Vui lòng Đăng ký/Đăng nhập trước khi vào phòng.");
            out.println("• Đăng ký: dùng giao diện client (nút Register) hoặc gõ: /register <username> <password>");
            out.println("• Đăng nhập: dùng giao diện client (nút Login) hoặc gõ: /login <username> <password>");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // ======= CHƯA LOGIN: chỉ cho phép /register, /login, /quit, /help =======
                if (!authenticated) {
                    if (line.startsWith("/register ")) {
                        handleRegister(line);
                        continue;
                    } else if (line.startsWith("/login ")) {
                        if (handleLogin(line)) {
                            // Sau khi login thành công, thêm vào phòng & thông báo
                            this.playerName = this.username;
                            room.addPlayer(playerName, this);
                            sendMessage("[AUTH_OK] Đăng nhập thành công. Chào " + username + "!");
                            room.broadcast("👤 " + playerName + " đã tham gia phòng.");
                            room.promptPendingForPhaseForAll();
                            // -> thông báo danh sách cho web qua Bridge
                            broadcastPlayersList();
                        }
                        continue;
                    } else if (line.equalsIgnoreCase("/help")) {
                        sendAuthHelp();
                        continue;
                    } else if (line.equalsIgnoreCase("/quit")) {
                        sendMessage("Goodbye.");
                        break;
                    } else {
                        // Không cho chat/command khác trước khi login
                        sendAuthHelp();
                        continue;
                    }
                }

                // ======= ĐÃ LOGIN: xử lý lệnh và chat =======
                if (line.startsWith("/")) {
                    // ====== LỆNH CÓ DẤU / ======
                    String[] parts = line.split("\\s+", 2);
                    String cmd = parts[0].toLowerCase();
                    String arg = parts.length > 1 ? parts[1].trim() : "";

                    switch (cmd) {
                        case "/help" -> {
                            sendMessage("Lệnh: /help, /players, /role, /start, /day, /endday, /night, /endnight, /vote <tên>,");
                            sendMessage("       /kill <tên> (Mafia), /save <tên> (Doctor), /investigate <tên> (Detective), /protect <tên> (Bodyguard), /quit");
                        }

                        case "/start" -> {
                            room.startGame();
                            broadcastPlayersList();     // cho web list người chơi khi game bắt đầu
                        }

                        case "/startday", "/day" -> {
                            room.startDayPhase();
                            broadcastPhase("DAY START");
                        }
                        case "/endday" -> {
                            room.endDayPhase();
                            broadcastPhase("DAY END");
                        }

                        case "/startnight", "/night" -> {
                            room.startNightPhase();
                            broadcastPhase("NIGHT START");
                        }
                        case "/endnight" -> {
                            room.endNightPhase();
                            broadcastPhase("NIGHT END");
                        }

                        case "/vote" -> {
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /vote <tên>");
                            else room.castVote(playerName, arg);
                            pending = PendingAction.NONE;
                        }

                        case "/kill" -> {
                            if (arg.isEmpty()) { sendMessage("❌ Cú pháp: /kill <tên>"); }
                            else if (getRole() == Role.MAFIA) { room.recordNightAction(playerName, arg); }
                            else { sendMessage("❌ Chỉ Mafia mới có thể dùng /kill."); }
                            pending = PendingAction.NONE;
                        }

                        case "/save" -> {
                            if (arg.isEmpty()) { sendMessage("❌ Cú pháp: /save <tên>"); }
                            else if (getRole() == Role.DOCTOR) { room.recordNightAction(playerName, arg); }
                            else { sendMessage("❌ Chỉ Doctor mới có thể dùng /save."); }
                            pending = PendingAction.NONE;
                        }

                        case "/investigate" -> {
                            if (arg.isEmpty()) { sendMessage("❌ Cú pháp: /investigate <tên>"); }
                            else if (getRole() == Role.DETECTIVE) { room.recordNightAction(playerName, arg); }
                            else { sendMessage("❌ Chỉ Detective mới có thể dùng /investigate."); }
                            pending = PendingAction.NONE;
                        }

                        case "/protect" -> {
                            if (arg.isEmpty()) { sendMessage("❌ Cú pháp: /protect <tên>"); }
                            else if (getRole() == Role.BODYGUARD) { room.recordNightAction(playerName, arg); }
                            else { sendMessage("❌ Chỉ Bodyguard mới có thể dùng /protect."); }
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
                            break;
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
            if (authenticated && playerName != null) {
                room.removePlayer(playerName);
                room.broadcast("❌ " + playerName + " đã ngắt kết nối.");
                broadcastPlayersList(); // cập nhật danh sách cho web
            }
        }
    }

    // ===== AUTH handlers =====
    private void sendAuthHelp() {
        sendMessage("Bạn chưa đăng nhập. Dùng UI client hoặc gõ lệnh:");
        sendMessage("• /register <username> <password>");
        sendMessage("• /login <username> <password>");
        sendMessage("• /help, /quit");
    }

    /** Xử lý đăng ký, KHÔNG tự đăng nhập; trả [REGISTER_OK] nếu thành công */
    private void handleRegister(String line) {
        String[] sp = line.trim().split("\\s+", 3);
        if (sp.length < 3) {
            sendMessage("Usage: /register <username> <password>");
            return;
        }
        String u = sp[1], p = sp[2];

        // Không cho trùng người đang online (nếu đã ở phòng)
        Player existing = room.getPlayer(u);
        if (existing != null && existing.isAlive()) {
            sendMessage("[AUTH_FAIL] Tên này đang online, hãy chọn tên khác.");
            return;
        }

        String err = AUTH.register(u, p);
        if (err == null) {
            sendMessage("[REGISTER_OK] Đăng ký thành công. Hãy đăng nhập: /login " + u + " <password>");
        } else {
            sendMessage("[AUTH_FAIL] " + err);
        }
    }

    /** Xử lý đăng nhập; trả true nếu thành công (KHÔNG gửi [AUTH_OK] ở đây) */
    private boolean handleLogin(String line) {
        String[] sp = line.trim().split("\\s+", 3);
        if (sp.length < 3) {
            sendMessage("Usage: /login <username> <password>");
            return false;
        }
        String u = sp[1], p = sp[2];

        // chặn login khi username đang online
        Player existing = room.getPlayer(u);
        if (existing != null && existing.isAlive()) {
            sendMessage("[AUTH_FAIL] Tên này đang online. Nếu là bạn, hãy đợi phiên trước thoát.");
            return false;
        }

        if (AUTH.login(u, p)) {
            this.authenticated = true;
            this.username = u;
            return true;
        } else {
            sendMessage("[AUTH_FAIL] Sai username hoặc mật khẩu.");
            return false;
        }
    }

    // ===== Helpers =====
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

    // ==================== BỔ SUNG: broadcast text cho web (qua Bridge) ====================

    /** Gửi: "PLAYERS: name1, name2, ..." để HTML cập nhật danh sách */
    private void broadcastPlayersList() {
        List<String> names = room.getPlayersAll().stream()
                .map(this::safePlayerName)       // cố gắng lấy tên chuẩn
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
        room.broadcast("PLAYERS: " + String.join(", ", names));
    }

    /** Gửi: "PHASE: ..." để HTML biết trạng thái */
    private void broadcastPhase(String text) {
        room.broadcast("PHASE: " + text);
    }

    /** Thử lấy tên người chơi; nếu lớp Player không có getName(), fallback toString() */
    private String safePlayerName(Player p) {
        try {
            return (String) p.getClass().getMethod("getName").invoke(p);
        } catch (Exception ignore) {
            return p.toString();
        }
    }
}
