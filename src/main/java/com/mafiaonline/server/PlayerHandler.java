package com.mafiaonline.server;

import java.io.*;
import java.net.Socket;

/**
 * Handle one client connection.
 * Commands (start with '/'):
 *  /start, /vote <name>, /endday, /kill <name>, /save <name>, /investigate <name>, /protect <name>,
 *  /players, /role, /quit
 * Chat: any line not starting with '/'
 */
public class PlayerHandler extends Thread {
    private final Socket socket;
    private final GameRoom room;
    private PrintWriter out;
    private BufferedReader in;
    private String playerName;
    private Role role = Role.UNASSIGNED;

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
                    String[] parts = line.split("\\s+", 2);
                    String cmd = parts[0].toLowerCase();
                    String arg = parts.length > 1 ? parts[1].trim() : "";

                    switch (cmd) {
                        case "/start":
                            room.startGame();
                            break;
                        case "/startday":
                        case "/day":
                            room.startDayPhase();
                            break;
                        case "/endday":
                            room.endDayPhase();
                            break;
                        case "/startnight":
                        case "/night":
                            room.startNightPhase();
                            break;
                        case "/endnight":
                            room.endNightPhase();
                            break;
                        case "/vote":
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /vote <tên>");
                            else room.castVote(playerName, arg);
                            break;
                        case "/kill":
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /kill <tên>");
                            else {
                                if (getRole() == Role.MAFIA) room.recordNightAction(playerName, arg);
                                else sendMessage("❌ Chỉ Mafia mới có thể dùng /kill.");
                            }
                            break;
                        case "/save":
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /save <tên>");
                            else {
                                if (getRole() == Role.DOCTOR) room.recordNightAction(playerName, arg);
                                else sendMessage("❌ Chỉ Doctor mới có thể dùng /save.");
                            }
                            break;
                        case "/investigate":
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /investigate <tên>");
                            else {
                                if (getRole() == Role.DETECTIVE) room.recordNightAction(playerName, arg);
                                else sendMessage("❌ Chỉ Detective mới có thể dùng /investigate.");
                            }
                            break;
                        case "/protect":
                            if (arg.isEmpty()) sendMessage("❌ Cú pháp: /protect <tên>");
                            else {
                                if (getRole() == Role.BODYGUARD) room.recordNightAction(playerName, arg);
                                else sendMessage("❌ Chỉ Bodyguard mới có thể dùng /protect.");
                            }
                            break;
                        case "/players":
                            sendMessage("Players:" + room.getPlayersAll().stream().map(Player::toString).reduce("", (a,b)->a+"\n"+b));
                            break;
                        case "/role":
                            sendMessage("🎭 Role: " + getRole());
                            break;
                        case "/quit":
                            sendMessage("Goodbye.");
                            socket.close();
                            return;
                        default:
                            sendMessage("❌ Lệnh không hợp lệ: " + cmd);
                    }
                } else {
                    // normal chat
                    room.broadcast(playerName + ": " + line);
                }
            }

        } catch (IOException e) {
            System.out.println("[PlayerHandler] Lỗi socket cho " + playerName + " : " + e.getMessage());
        } finally {
            if (playerName != null) {
                room.removePlayer(playerName);
                room.broadcast("❌ " + playerName + " đã ngắt kết nối.");
            }
            try { socket.close(); } catch (Exception ignored) {}
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
}
