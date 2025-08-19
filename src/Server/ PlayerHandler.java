package com.mafiaonline.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class PlayerHandler implements Runnable {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    public PlayerHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println("Welcome to Mafia Online!");

            String msg;
            while ((msg = in.readLine()) != null) {
                System.out.println("Message from client: " + msg);
                out.println("Server received: " + msg);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
                System.out.println("Player disconnected.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
