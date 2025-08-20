package com.mafiaonline;

import com.mafiaonline.common.*;

public class TestJson {
    public static void main(String[] args) throws Exception {
        // JSON string nhận từ client
        String json = "{ \"type\": \"CHAT\", \"sender\": \"user123\", \"content\": \"Xin chào!\", \"timestamp\": 1690000123456 }";

        // Chuyển JSON → Object
        Message message = JsonUtil.fromJson(json);
        System.out.println("Loại tin nhắn: " + message.getType());
        System.out.println("Người gửi: " + message.getSender());
        System.out.println("Nội dung: " + message.getContent());

        // Chuyển Object → JSON
        Message newMsg = new Message(MessageType.VOTE, "user456", "vote user123", System.currentTimeMillis());
        String newJson = JsonUtil.toJson(newMsg);
        System.out.println("JSON gửi đi: " + newJson);
    }
}
