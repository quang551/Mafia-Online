package com.mafiaonline.server;

import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * ServerMain (Day2 left simple).
 * - Still accepts connections and sends greetings (unchanged).
 * - Network integration and PlayerHandler will be introduced in later days.
 */
public class ServerMain {
    private static final int PORT = 12345;

    public static void main(String[] args) {
        GameRoom room = new GameRoom();
        PhaseManager phase = new PhaseManager(room);

        System.out.println("🚀 Mafia-Online Server (Day2) starting on port " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("✅ Server listening on port " + PORT);

            while (true) {
                Socket client = serverSocket.accept();
                System.out.println("🔗 Client connected: " + client.getRemoteSocketAddress());

                try {
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    out.println("Xin chào! (Day2) Server hiện chỉ hỗ trợ test GameRoom logic.");
                    out.println("Bạn có thể dùng TestGameRoom trong project để test logic mà không cần client.");
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    client.close();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
