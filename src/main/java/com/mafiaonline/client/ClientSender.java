package com.mafiaonline.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class ClientSender {
    private Socket socket;

    public ClientSender(Socket socket) {
        this.socket = socket;
    }

    public void start() {
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Scanner scanner = new Scanner(System.in);
            while (true) {
                String msg = scanner.nextLine();
                out.println(msg);
                if (msg.equalsIgnoreCase("quit")) {
                    break;
                }
            }
            socket.close();
            scanner.close();
        } catch (IOException e) {
            System.err.println("[ClientSender] Lỗi gửi dữ liệu: " + e.getMessage());
        }
    }
}
