package com.mafiaonline.common;

public enum MessageType {
    JOIN,       // Người chơi vào phòng
    LEAVE,      // Rời phòng
    CHAT,       // Chat công khai
    PRIVATE_CHAT, // Chat bí mật (mafia ban đêm)
    VOTE,       // Vote treo cổ
    KILL,       // Mafia giết
    HEAL,       // Bác sĩ cứu
    INVESTIGATE,// Cảnh sát điều tra
    START_GAME, // Bắt đầu game
    END_GAME,   // Kết thúc game
    SYSTEM,     // Tin nhắn hệ thống
    ERROR       // Báo lỗi
}
