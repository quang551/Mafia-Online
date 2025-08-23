package com.mafiaonline.client;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class SimpleSwingClient extends JFrame {
    private final JTextArea log = new JTextArea();
    private final JTextField input = new JTextField();
    private final JButton btnSend = new JButton("Gửi");
    private final JButton btnPlayers = new JButton("/players");
    private final JButton btnRole = new JButton("/role");
    private final JButton btnStart = new JButton("/start");
    private final JButton btnDay = new JButton("/day");
    private final JButton btnEndDay = new JButton("/endday");
    private final JButton btnNight = new JButton("/night");
    private final JButton btnEndNight = new JButton("/endnight");

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    public SimpleSwingClient(String host, int port) {
        super("Mafia-Online Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(720, 520);
        setLocationRelativeTo(null);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(btnPlayers);
        top.add(btnRole);
        top.add(btnStart);
        top.add(btnDay);
        top.add(btnEndDay);
        top.add(btnNight);
        top.add(btnEndNight);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(btnSend, BorderLayout.EAST);

        setLayout(new BorderLayout(8, 8));
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(log), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        // Sự kiện gửi
        ActionListener doSend = e -> {
            String text = input.getText().trim();
            if (text.isEmpty() || out == null) return;
            out.println(text);
            input.setText("");
        };
        btnSend.addActionListener(doSend);
        input.addActionListener(doSend);

        // Nút lệnh nhanh
        btnPlayers.addActionListener(e -> sendCmd("/players"));
        btnRole.addActionListener(e -> sendCmd("/role"));
        btnStart.addActionListener(e -> sendCmd("/start"));
        btnDay.addActionListener(e -> sendCmd("/day"));
        btnEndDay.addActionListener(e -> sendCmd("/endday"));
        btnNight.addActionListener(e -> sendCmd("/night"));
        btnEndNight.addActionListener(e -> sendCmd("/endnight"));

        // Kết nối
        new Thread(() -> connect(host, port)).start();
    }

    private void connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            append("Đã kết nối tới " + host + ":" + port);
            // Luồng đọc từ server
            new Thread(() -> {
                String line;
                try {
                    while ((line = in.readLine()) != null) {
                        append(line);
                    }
                } catch (IOException ex) {
                    append("[Mất kết nối] " + ex.getMessage());
                } finally {
                    closeQuietly();
                }
            }).start();
        } catch (IOException e) {
            append("Không thể kết nối: " + e.getMessage());
        }
    }

    private void sendCmd(String cmd) {
        if (out != null) {
            out.println(cmd);
        }
    }

    private void append(String s) {
        SwingUtilities.invokeLater(() -> {
            log.append(s + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = 12345;
        if (args.length >= 1) host = args[0];
        if (args.length >= 2) port = Integer.parseInt(args[1]);

        String h = host; int p = port;
        SwingUtilities.invokeLater(() -> new SimpleSwingClient(h, p).setVisible(true));
    }
}
