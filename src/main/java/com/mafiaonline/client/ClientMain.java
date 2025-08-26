package com.mafiaonline.client;

import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

public class ClientMain {
    public static void main(String[] args) {
        String host = "localhost";
        int port = 12345;

        try {
            Socket socket = new Socket(host, port);
            System.out.println("[Client] Đã kết nối tới server " + host + ":" + port);

            new Thread(new ClientListener(socket)).start();

            ClientSender sender = new ClientSender(socket);
            sender.start();

        } catch (IOException e) {
            System.err.println("[Client] Không thể kết nối tới server: " + e.getMessage());
        }
    }
}