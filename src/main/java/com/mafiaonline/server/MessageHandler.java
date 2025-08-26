package com.mafiaonline.common;

public class MessageHandler {

    /**
     * Xử lý message JSON từ client gửi lên
     */
    public void handleMessage(String json) {
        try {
            // 1. Chuyển JSON → Object
            Message message = JsonUtil.fromJson(json);

            // 2. Switch theo loại message
            switch (message.getType()) {
                case JOIN:
                    handleJoin(message);
                    break;
                case LEAVE:
                    handleLeave(message);
                    break;
                case CHAT:
                    handleChat(message);
                    break;
                case PRIVATE_CHAT:
                    handlePrivateChat(message);
                    break;
                case VOTE:
                    handleVote(message);
                    break;
                case KILL:
                    handleKill(message);
                    break;
                case HEAL:
                    handleHeal(message);
                    break;
                case INVESTIGATE:
                    handleInvestigate(message);
                    break;
                case START_GAME:
                    handleStartGame(message);
                    break;
                case END_GAME:
                    handleEndGame(message);
                    break;
                case SYSTEM:
                    handleSystem(message);
                    break;
                case ERROR:
                    handleError(message);
                    break;
                default:
                    System.out.println("❓ Unknown message type: " + message.getType());
            }

        } catch (Exception e) {
            System.out.println("⚠️ Lỗi khi xử lý JSON: " + e.getMessage());
        }
    }

    // ==== Các hàm xử lý cụ thể ====

    private void handleJoin(Message message) {
        System.out.println("[JOIN] " + message.getSender() + " đã tham gia phòng.");
    }

    private void handleLeave(Message message) {
        System.out.println("[LEAVE] " + message.getSender() + " đã rời phòng.");
    }

    private void handleChat(Message message) {
        System.out.println("[CHAT] " + message.getSender() + ": " + message.getContent());
    }

    private void handlePrivateChat(Message message) {
        System.out.println("[PRIVATE CHAT] " + message.getSender() + " (mafia chat): " + message.getContent());
    }

    private void handleVote(Message message) {
        System.out.println("[VOTE] " + message.getSender() + " vote " + message.getContent());
    }

    private void handleKill(Message message) {
        System.out.println("[KILL] Mafia chọn giết " + message.getContent());
    }

    private void handleHeal(Message message) {
        System.out.println("[HEAL] Bác sĩ cứu " + message.getContent());
    }

    private void handleInvestigate(Message message) {
        System.out.println("[INVESTIGATE] Cảnh sát điều tra " + message.getContent());
    }

    private void handleStartGame(Message message) {
        System.out.println("[START] Game bắt đầu!");
    }

    private void handleEndGame(Message message) {
        System.out.println("[END] Game kết thúc!");
    }

    private void handleSystem(Message message) {
        System.out.println("[SYSTEM] " + message.getContent());
    }

    private void handleError(Message message) {
        System.out.println("[ERROR] " + message.getContent());
    }
}
