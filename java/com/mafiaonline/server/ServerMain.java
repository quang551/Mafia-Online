package com.mafiaonline.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ServerMain {
    private static final int PORT = 12345; // Cổng server

    public static void main(String[] args) {
        System.out.println("🚀 Khởi động Mafia Online Server...");

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("✅ Server đang chạy tại cổng " + PORT);

            // Lắng nghe client (chỉ chấp nhận kết nối, chưa xử lý logic chat)
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("🔗 Client đã kết nối: " + clientSocket.getInetAddress());
                // TODO: Sau này sẽ tạo thread riêng cho client
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
