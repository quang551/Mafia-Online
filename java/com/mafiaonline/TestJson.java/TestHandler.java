package com.mafiaonline;

import com.mafiaonline.common.MessageHandler;

public class TestHandler {
    public static void main(String[] args) {
        MessageHandler handler = new MessageHandler();

        // Ví dụ 1: Người chơi join
        String joinJson = "{ \"type\": \"JOIN\", \"sender\": \"user123\", \"content\": \"\", \"timestamp\": 1690000123456 }";
        handler.handleMessage(joinJson);

        // Ví dụ 2: Người chơi chat
        String chatJson = "{ \"type\": \"CHAT\", \"sender\": \"user123\", \"content\": \"Xin chào mọi người!\", \"timestamp\": 1690000123456 }";
        handler.handleMessage(chatJson);

        // Ví dụ 3: Mafia giết
        String killJson = "{ \"type\": \"KILL\", \"sender\": \"mafia1\", \"content\": \"user456\", \"timestamp\": 1690000123456 }";
        handler.handleMessage(killJson);
    }
}
