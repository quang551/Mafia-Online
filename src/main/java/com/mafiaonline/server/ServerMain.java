package com.mafiaonline.server;

import com.mafiaonline.bridge.WsBridgeServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Server entrypoint */
public class ServerMain {
    public static void main(String[] args) {
        int tcpPort = 12345;
        int wsPort  = 8080;
        String wsPath = "/ws";
        String tcpHostForBridge = "127.0.0.1";

        if (args.length >= 1) try { tcpPort = Integer.parseInt(args[0]); } catch (Exception ignore) {}
        if (args.length >= 2) try { wsPort  = Integer.parseInt(args[1]); } catch (Exception ignore) {}

        GameRoom room = new GameRoom();
        ServerSocket serverSocket = null;

        System.out.println("[Server] Starting Mafia-Online TCP on port " + tcpPort + " ...");
        System.out.println("[Server] Starting WS Bridge at ws://0.0.0.0:" + wsPort + wsPath + " -> " + tcpHostForBridge + ":" + tcpPort);

        // Bridge (4 tham số)
        WsBridgeServer bridge = new WsBridgeServer(
                new InetSocketAddress("0.0.0.0", wsPort),
                wsPath,
                tcpHostForBridge,
                tcpPort
        );
        bridge.start();

        try {
            serverSocket = new ServerSocket(tcpPort);

            // shutdown hook
            ServerSocket finalServerSocket = serverSocket;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Server] Shutdown initiated...");
                try {
                    if (finalServerSocket != null && !finalServerSocket.isClosed()) finalServerSocket.close();
                } catch (IOException e) { System.err.println("[Server] Error closing ServerSocket: " + e.getMessage()); }
                try {
                    if (room != null && room.getPhaseManager() != null) room.getPhaseManager().shutdownScheduler();
                } catch (Exception ex) { System.err.println("[Server] Error shutting down PhaseManager: " + ex.getMessage()); }
                System.out.println("[Server] Goodbye.");
            }));

            System.out.println("[Server] Listening TCP on port " + tcpPort);
            while (true) {
                try {
                    Socket client = serverSocket.accept();
                    System.out.println("[Server] New connection from " + client.getRemoteSocketAddress());
                    PlayerHandler handler = new PlayerHandler(client, room);
                    handler.start();
                } catch (IOException acceptEx) {
                    System.out.println("[Server] Stopped accepting connections (" + acceptEx.getMessage() + "). Exiting accept loop.");
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Failed to start: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); } catch (IOException ignore) {}
            try { if (room != null && room.getPhaseManager() != null) room.getPhaseManager().shutdownScheduler(); } catch (Exception ignore) {}
        }
    }
}
