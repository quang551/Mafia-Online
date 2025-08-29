package com.mafiaonline.clientfx;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class NetClient {
    private final String host;
    private final int port;
    private final java.util.function.Consumer<String> onLine;
    private final java.util.function.Consumer<String> onDisconnect;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "net-listener");
        t.setDaemon(true);
        return t;
    });

    NetClient(String host, int port,
              java.util.function.Consumer<String> onLine,
              java.util.function.Consumer<String> onDisconnect) {
        this.host = host; this.port = port; this.onLine = onLine; this.onDisconnect = onDisconnect;
    }

    void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        ioPool.submit(this::listenLoop);
    }

    boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    void send(String line) {
        if (out != null) out.println(line);
    }

    private void listenLoop() {
        String line;
        try {
            while ((line = in.readLine()) != null) {
                onLine.accept(line);
            }
            onDisconnect.accept("EOF");
        } catch (IOException ex) {
            onDisconnect.accept(ex.getMessage());
        } finally {
            close();
        }
    }

    void close() {
        try { if (out != null) out.flush(); } catch (Exception ignore) {}
        try { if (socket != null) socket.close(); } catch (Exception ignore) {}
    }
}
