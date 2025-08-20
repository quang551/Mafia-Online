package com.mafiaonline.server;

import java.io.*;
import java.net.Socket;

public class PlayerHandler extends Thread {
    private final Socket socket;
    private final GameRoom room;
    private PrintWriter out;
    private BufferedReader in;
    private String playerName;
    private Role role;

    public PlayerHandler(Socket socket, GameRoom room) {
        this.socket = socket;
        this.room = room;
    }

    public void setRole(Role role) { this.role = role; }
    public Role getRole() { return role; }

    public void sendMessage(String msg) {
        if (out != null) out.println(msg);
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println("👉 Nhập tên của bạn:");
            playerName = in.readLine();
            if (playerName == null || playerName.trim().isEmpty()) {
                playerName = "Player" + System.currentTimeMillis()%10000;
            }
            playerName = playerName.trim();

            room.addPlayer(playerName, this);
            // set handler in Player object is done in GameRoom.addPlayer
            out.println("Chào " + playerName + "! Viết tin nhắn để chat. Dùng lệnh /help để xem lệnh.");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("/")) {
                    handleCommand(line);
                } else {
                    // normal chat: broadcast only alive players can chat (optionally allow dead chat)
                    room.broadcast(playerName + ": " + line);
                }

                // if game ended, allow chat but commands may be ignored
                if (room.getState() == GameState.END) {
                    // keep connection open
                }
            }
        } catch (IOException e) {
            System.err.println("Connection error with " + playerName + ": " + e.getMessage());
        } finally {
            try {
                if (playerName != null) {
                    room.removePlayer(playerName);
                }
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void handleCommand(String cmdFull) {
        String[] parts = cmdFull.split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/help":
                sendMessage("Lệnh: /start /night /endnight /kill <tên> /save <tên> /investigate <tên> /protect <tên> /vote <tên> /endday /status");
                break;

            case "/start":
                room.startGame();
                break;

            case "/night":
                room.startNight();
                break;

            case "/endnight":
                room.endNight();
                break;

            case "/kill":
                if (role == Role.MAFIA) {
                    if (!arg.isEmpty()) room.recordNightAction(playerName, arg);
                    else sendMessage("✅ Cú pháp: /kill <tên>");
                } else sendMessage("❌ Bạn không phải Mafia.");
                break;

            case "/save":
                if (role == Role.DOCTOR) {
                    if (!arg.isEmpty()) room.recordNightAction(playerName, arg);
                    else sendMessage("✅ Cú pháp: /save <tên>");
                } else sendMessage("❌ Bạn không phải Doctor.");
                break;

            case "/investigate":
                if (role == Role.DETECTIVE) {
                    if (!arg.isEmpty()) room.recordNightAction(playerName, arg);
                    else sendMessage("✅ Cú pháp: /investigate <tên>");
                } else sendMessage("❌ Bạn không phải Detective.");
                break;

            case "/protect":
                if (role == Role.BODYGUARD) {
                    if (!arg.isEmpty()) {
                        room.getPhaseManager().recordNightAction(playerName, arg);
                    } else sendMessage("✅ Cú pháp: /protect <tên>");
                } else sendMessage("❌ Bạn không phải Bodyguard.");
                break;

            case "/vote":
                if (!arg.isEmpty()) {
                    room.castVote(playerName, arg);
                } else sendMessage("✅ Cú pháp: /vote <tên>");
                break;

            case "/endday":
                room.endDay();
                break;

            case "/status":
                StringBuilder s = new StringBuilder("=== Trạng thái ===\n");
                s.append("GameState: ").append(room.getState()).append("\n");
                s.append("Players (alive):\n");
                for (Player p : room.getPlayersAlive()) {
                    s.append(" - ").append(p.getName()).append(" (").append(p.getRole()).append(")\n");
                }
                sendMessage(s.toString());
                break;

            default:
                sendMessage("❓ Lệnh không rõ. /help để xem lệnh.");
        }
    }
}
