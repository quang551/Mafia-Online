package com.mafiaonline.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Server entrypoint.
 * - Khởi tạo GameRoom
 * - Lắng nghe kết nối, mỗi client tạo 1 PlayerHandler thread
 * - Shutdown hook để dọn sạch scheduler (PhaseManager)
 */
public class ServerMain {
    public static void main(String[] args) {
        int port = 12345; // có thể lấy từ args/env nếu muốn
        GameRoom room = new GameRoom();
        ServerSocket serverSocket = null;

        System.out.println("[Server] Starting Mafia-Online on port " + port + " ...");

        try {
            serverSocket = new ServerSocket(port);

            // Add shutdown hook to cleanup scheduler and close server socket
            ServerSocket finalServerSocket = serverSocket;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Server] Shutdown initiated...");
                try {
                    if (finalServerSocket != null && !finalServerSocket.isClosed()) {
                        finalServerSocket.close();
                        System.out.println("[Server] ServerSocket closed.");
                    }
                } catch (IOException e) {
                    System.err.println("[Server] Error closing ServerSocket: " + e.getMessage());
                }

                // shutdown scheduled tasks in PhaseManager
                try {
                    if (room != null && room.getPhaseManager() != null) {
                        room.getPhaseManager().shutdownScheduler();
                        System.out.println("[Server] PhaseManager scheduler shutdown.");
                    }
                } catch (Exception ex) {
                    System.err.println("[Server] Error shutting down PhaseManager: " + ex.getMessage());
                }
                System.out.println("[Server] Goodbye.");
            }));

            System.out.println("[Server] Listening on port " + port);

            // Accept loop
            while (true) {
                try {
                    Socket client = serverSocket.accept();
                    System.out.println("[Server] New connection from " + client.getRemoteSocketAddress());
                    PlayerHandler handler = new PlayerHandler(client, room);
                    handler.start();
                } catch (IOException acceptEx) {
                    // If serverSocket was closed by shutdown hook, accept throws SocketException / IOException
                    System.out.println("[Server] Stopped accepting connections (" + acceptEx.getMessage() + "). Exiting accept loop.");
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Failed to start: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // final cleanup (in case shutdown hook didn't run)
            try {
                if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
            } catch (IOException ignored) {}
            try {
                if (room != null && room.getPhaseManager() != null) room.getPhaseManager().shutdownScheduler();
            } catch (Exception ignored) {}
        }
    }
}
