package com.mafiaonline.server;

import java.util.StringTokenizer;

/**
 * MessageHandler chịu trách nhiệm xử lý tin nhắn từ client.
 * Có thể là chat bình thường hoặc lệnh điều khiển game (/vote, /kill, /heal...).
 */
public class MessageHandler {
    private final GameRoom gameRoom;

    public MessageHandler(GameRoom gameRoom) {
        this.gameRoom = gameRoom;
    }

    /**
     * Xử lý tin nhắn client gửi lên server
     * @param sender tên người gửi
     * @param msg nội dung tin nhắn
     */
    public void handleMessage(String sender, String msg) {
        if (msg == null || msg.trim().isEmpty()) return;

        // Nếu bắt đầu bằng "/", coi là lệnh
        if (msg.startsWith("/")) {
            handleCommand(sender, msg);
        } else {
            // Nếu không thì broadcast chat bình thường
            gameRoom.broadcast("💬 " + sender + ": " + msg);
        }
    }

    private void handleCommand(String sender, String msg) {
        StringTokenizer st = new StringTokenizer(msg);
        String command = st.nextToken().toLowerCase();

        switch (command) {
            case "/vote": {
                if (st.hasMoreTokens()) {
                    String target = st.nextToken();
                    gameRoom.castVote(sender, target);
                } else {
                    gameRoom.privateMessage(sender, "⚠️ Dùng: /vote <tên người chơi>");
                }
                break;
            }

            case "/kill": {
                if (st.hasMoreTokens()) {
                    String target = st.nextToken();
                    gameRoom.recordNightAction(sender, target);
                } else {
                    gameRoom.privateMessage(sender, "⚠️ Dùng: /kill <tên người chơi>");
                }
                break;
            }

            case "/heal": {
                if (st.hasMoreTokens()) {
                    String target = st.nextToken();
                    gameRoom.recordNightAction(sender, target);
                } else {
                    gameRoom.privateMessage(sender, "⚠️ Dùng: /heal <tên người chơi>");
                }
                break;
            }

            case "/start": {
                gameRoom.startGame();
                break;
            }

            case "/list": {
                gameRoom.printPlayers();
                gameRoom.privateMessage(sender,
                        "👥 Có " + gameRoom.getPlayersAll().size() + " người trong phòng.");
                break;
            }

            case "/role": {
                Role r = gameRoom.getPlayerRole(sender);
                if (r != null) {
                    gameRoom.privateMessage(sender, "🎭 Vai trò của bạn: " + r);
                }
                break;
            }

            default:
                gameRoom.privateMessage(sender,
                        "❓ Lệnh không hợp lệ: " + command);
                break;
        }
    }
}
