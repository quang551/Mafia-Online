package com.mafiaonline.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientMain {
    public static void main(String[] args) {
        String host = (args.length >= 1) ? args[0] : "localhost";
        int port = (args.length >= 2) ? Integer.parseInt(args[1]) : 12345;

        System.out.println("[Client] Connecting to " + host + ":" + port + " ...");
        try (Socket socket = new Socket(host, port)) {
            // I/O UTF-8
            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out    = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // Thread nhận tin từ server
            Thread listener = new Thread(new ClientListener(in), "ClientListener");
            listener.setDaemon(true);
            listener.start();

            // Thread đọc bàn phím & gửi lên server
            Thread sender = new Thread(new ClientSender(out, socket), "ClientSender");
            sender.start();

            // Chờ tới khi gửi xong (user /quit hoặc đóng cửa sổ)
            sender.join();
            System.out.println("[Client] Bye.");
        } catch (Exception e) {
            System.out.println("[Client] Cannot connect: " + e.getMessage());
        }
    }
}
