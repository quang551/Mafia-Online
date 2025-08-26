package com.mafiaonline.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientSender implements Runnable {
    private final PrintWriter out;
    private final Socket socket;

    public ClientSender(PrintWriter out, Socket socket) {
        this.out = out;
        this.socket = socket;
    }

    @Override
    public void run() {
        try (BufferedReader console =
                     new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = console.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // /quit để rời phòng và đóng socket
                if (line.equalsIgnoreCase("/quit") || line.equalsIgnoreCase("exit")) {
                    out.println("/quit");
                    break;
                }

                // Gửi nguyên văn tới server (tên/command/chat)
                out.println(line);
            }
        } catch (IOException e) {
            System.out.println("[Client] Input error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}
