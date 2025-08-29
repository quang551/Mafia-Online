package com.mafiaonline.client;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ClientSender implements Runnable {
    private final Socket socket;

    public ClientSender(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (PrintWriter out = new PrintWriter(
                 new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
             Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            System.out.println("[Client] Gõ tin nhắn, 'quit' để thoát:");
            while (true) {
                if (!scanner.hasNextLine()) break;
                String msg = scanner.nextLine();
                out.println(msg);
                if ("quit".equalsIgnoreCase(msg.trim())) break;
            }
        } catch (IOException e) {
            System.err.println("[ClientSender] Lỗi gửi dữ liệu: " + e.getMessage());
        }
    }
}
