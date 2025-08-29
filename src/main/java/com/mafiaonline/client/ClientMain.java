package com.mafiaonline.client;

import java.io.IOException;
import java.net.Socket;

public class ClientMain {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port;
        try {
            port = args.length > 1 ? Integer.parseInt(args[1]) : 12345;
        } catch (NumberFormatException ex) {
            System.err.println("[Client] Port không hợp lệ, dùng mặc định 12345");
            port = 12345;
        }

        System.out.printf("[Client] Kết nối tới %s:%d...%n", host, port);
        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);

            Thread listener = new Thread(new ClientListener(socket), "client-listener");
            Thread sender   = new Thread(new ClientSender(socket),   "client-sender");

            listener.start();
            sender.start();

            sender.join();
            try { socket.shutdownOutput(); } catch (IOException ignore) {}
            listener.join();
        } catch (Exception e) {
            System.err.println("[Client] Lỗi: " + e.getMessage());
        }
        System.out.println("[Client] Thoát.");
    }
}
