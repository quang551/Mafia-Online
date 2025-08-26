package com.mafiaonline.client;

import java.io.BufferedReader;
import java.io.IOException;

public class ClientListener implements Runnable {
    private final BufferedReader in;

    public ClientListener(BufferedReader in) {
        this.in = in;
    }

    @Override
    public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
            System.out.println("[Client] Server closed the connection.");
        } catch (IOException e) {
            System.out.println("[Client] Disconnected: " + e.getMessage());
        }
    }
}
