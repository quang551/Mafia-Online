package com.mafiaonline.server;

import com.mafiaonline.bridge.WsBridgeServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Server entrypoint (TCP game + WS bridge) */
public class ServerMain {
    public static void main(String[] args) {
        // ===== Default ports/path/host =====
        int tcpPort = 12345;                   // cổng TCP game
        int wsPort  = 8080;                    // cổng WebSocket bridge
        String wsPath = "/ws";                 // path WebSocket
        String tcpHostForBridge = "127.0.0.1"; // bridge sẽ nối tới TCP host này

        // ===== CLI args (tùy chọn) =====
        // Cách 1: 2 tham số: <tcpPort> <wsPort>
        if (args.length >= 1) try { tcpPort = Integer.parseInt(args[0]); } catch (Exception ignore) {}
        if (args.length >= 2) try { wsPort  = Integer.parseInt(args[1]); } catch (Exception ignore) {}
        // Cách 2: flags linh hoạt
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--tcp-port" -> { try { tcpPort = Integer.parseInt(args[++i]); } catch (Exception ignore) {} }
                case "--ws-port"  -> { try { wsPort  = Integer.parseInt(args[++i]); } catch (Exception ignore) {} }
                case "--ws-path"  -> wsPath = args[++i];
                case "--tcp-host" -> tcpHostForBridge = args[++i];
            }
        }

        GameRoom room = new GameRoom();
        ServerSocket serverSocket = null;
        WsBridgeServer bridge = null;

        System.out.println("[Server] Starting Mafia-Online TCP on port " + tcpPort + " ...");
        System.out.println("[Server] Starting WS Bridge at ws://0.0.0.0:" + wsPort + wsPath + " -> " + tcpHostForBridge + ":" + tcpPort);

        // ===== Start WS bridge (1 WS : 1 TCP) =====
        try {
            bridge = new WsBridgeServer(
                    new InetSocketAddress("0.0.0.0", wsPort),
                    wsPath,
                    tcpHostForBridge,
                    tcpPort
            );
            bridge.start();
        } catch (Exception ex) {
            System.err.println("[Server] Failed to start WS Bridge: " + ex.getMessage());
            ex.printStackTrace();
            // vẫn tiếp tục TCP server nếu muốn; hoặc return để fail-fast
        }

        try {
            // Mở TCP server
            serverSocket = new ServerSocket(tcpPort);

            // ===== Shutdown hook: đóng WS bridge + TCP + scheduler =====
            WsBridgeServer finalBridge = bridge;
            ServerSocket finalServerSocket = serverSocket;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Server] Shutdown initiated...");
                // Đóng accept socket để break vòng lặp
                try {
                    if (finalServerSocket != null && !finalServerSocket.isClosed()) {
                        finalServerSocket.close();
                        System.out.println("[Server] ServerSocket closed.");
                    }
                } catch (IOException e) {
                    System.err.println("[Server] Error closing ServerSocket: " + e.getMessage());
                }
                // Dừng WS bridge
                try {
                    if (finalBridge != null) {
                        // WebSocketServer.stop() ném InterruptedException/IOException – bọc trong try/catch
                        finalBridge.stop(1000); // timeout 1s
                        System.out.println("[Server] WS Bridge stopped.");
                    }
                } catch (Exception e) {
                    System.err.println("[Server] Error stopping WS Bridge: " + e.getMessage());
                }
                // Dừng scheduler phase
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

            System.out.println("[Server] Listening TCP on port " + tcpPort);

            // ===== Accept loop =====
            while (true) {
                try {
                    Socket client = serverSocket.accept();
                    System.out.println("[Server] New connection from " + client.getRemoteSocketAddress());
                    PlayerHandler handler = new PlayerHandler(client, room);
                    handler.start();
                } catch (IOException acceptEx) {
                    // Khi serverSocket bị close (shutdown), accept sẽ ném exception -> thoát vòng lặp
                    System.out.println("[Server] Stopped accepting connections (" + acceptEx.getMessage() + "). Exiting accept loop.");
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Failed to start TCP server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup cuối cùng (nếu hook chưa chạy)
            try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); } catch (IOException ignore) {}
            try { if (room != null && room.getPhaseManager() != null) room.getPhaseManager().shutdownScheduler(); } catch (Exception ignore) {}
            try { if (bridge != null) bridge.stop(500); } catch (Exception ignore) {}
        }

        // ===== Banner hướng dẫn nhanh =====
        System.out.println("\n[How-To]");
        System.out.println("  Web client: http://localhost:8000/mafia-client.html");
        System.out.println("  Gateway   : ws://localhost:" + wsPort + wsPath);
        System.out.println("  LAN máy khác: http://<IP_máy_này>:8000/mafia-client.html & ws://<IP_máy_này>:" + wsPort + wsPath);
    }
}
