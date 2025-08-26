package com.mafiaonline.client;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class SimpleSwingClient extends JFrame {
    // Connection
    private JTextField tfHost;
    private JTextField tfPort;
    private JButton btnConnect;
    private JLabel lblConnStatus;

    // Auth
    private JTextField tfUsername;
    private JPasswordField pfPassword;
    private JButton btnRegister;
    private JButton btnLogin;

    // Chat
    private JTextArea taDisplay;
    private JTextField tfInput;
    private JButton btnSend;

    // Game controls
    private JButton btnPlayers, btnRole, btnStart, btnDay, btnEndDay, btnNight, btnEndNight;

    // Net
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;

    // State
    private volatile boolean connected = false;
    private volatile boolean authenticated = false;

    public SimpleSwingClient(String host, int port) {
        super("Mafia Online");

        // ===== LAF & UI defaults =====
        FlatLightLaf.setup();
        UIManager.put("Component.arc", 16);
        UIManager.put("Button.arc", 16);
        UIManager.put("TextComponent.arc", 12);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.trackArc", 999);

        setJMenuBar(buildMenuBar());

        // ===== App bar =====
        JPanel appBar = new JPanel(new BorderLayout());
        appBar.setBorder(new EmptyBorder(8, 12, 8, 12));
        JLabel title = new JLabel("Mafia-Online Client");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3));
        lblConnStatus = new JLabel("Disconnected");
        lblConnStatus.setOpaque(true);
        lblConnStatus.setBorder(new EmptyBorder(4, 8, 4, 8));
        setStatus(false);

        appBar.add(title, BorderLayout.WEST);
        appBar.add(lblConnStatus, BorderLayout.EAST);

        // ===== Cards: Connection & Account =====
        JPanel cards = new JPanel();
        cards.setLayout(new BoxLayout(cards, BoxLayout.X_AXIS));
        cards.setBorder(new EmptyBorder(8, 12, 8, 12));

        JPanel connectCard = new JPanel(new GridBagLayout());
        connectCard.setBorder(cardBorder("Connection"));
        GridBagConstraints gc = gbc();
        tfHost = new JTextField(host, 12);
        tfPort = new JTextField(String.valueOf(port), 5);
        btnConnect = new JButton("Connect");

        gc.gridx=0; gc.gridy=0; connectCard.add(new JLabel("Host"), gc);
        gc.gridx=1; connectCard.add(tfHost, gc);
        gc.gridx=2; connectCard.add(new JLabel("Port"), gc);
        gc.gridx=3; connectCard.add(tfPort, gc);
        gc.gridx=4; connectCard.add(btnConnect, gc);

        JPanel authCard = new JPanel(new GridBagLayout());
        authCard.setBorder(cardBorder("Account"));
        GridBagConstraints ga = gbc();
        tfUsername = new JTextField(12);
        pfPassword = new JPasswordField(12);
        btnRegister = new JButton("Register");
        btnLogin = new JButton("Login");

        ga.gridx=0; ga.gridy=0; authCard.add(new JLabel("Username"), ga);
        ga.gridx=1; authCard.add(tfUsername, ga);
        ga.gridx=2; authCard.add(new JLabel("Password"), ga);
        ga.gridx=3; authCard.add(pfPassword, ga);
        ga.gridx=4; authCard.add(btnRegister, ga);
        ga.gridx=5; authCard.add(btnLogin, ga);

        cards.add(connectCard);
        cards.add(Box.createHorizontalStrut(12));
        cards.add(authCard);

        // ===== Chat area =====
        taDisplay = new JTextArea(20, 70);
        taDisplay.setEditable(false);
        taDisplay.setLineWrap(true);
        taDisplay.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(taDisplay);
        scroll.setBorder(cardBorder("Room"));

        // ===== Input bar =====
        JPanel inputBar = new JPanel(new BorderLayout(8, 8));
        inputBar.setBorder(new EmptyBorder(8, 12, 12, 12));
        tfInput = new JTextField();
        btnSend = new JButton("Send");
        inputBar.add(tfInput, BorderLayout.CENTER);
        inputBar.add(btnSend, BorderLayout.EAST);

        // ===== Game controls (left rail) =====
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(cardBorder("Game Controls"));
        btnPlayers = new JButton("/players");
        btnRole    = new JButton("/role");
        btnStart   = new JButton("/start");
        btnDay     = new JButton("/day");
        btnEndDay  = new JButton("/endday");
        btnNight   = new JButton("/night");
        btnEndNight= new JButton("/endnight");
        for (JButton b : new JButton[]{btnPlayers,btnRole,btnStart,btnDay,btnEndDay,btnNight,btnEndNight}) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            controls.add(Box.createVerticalStrut(6));
            controls.add(b);
        }
        controls.add(Box.createVerticalStrut(6));

        // ===== Layout root =====
        JPanel center = new JPanel(new BorderLayout(12, 12));
        center.setBorder(new EmptyBorder(0, 12, 0, 12));
        center.add(scroll, BorderLayout.CENTER);
        center.add(inputBar, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(appBar, BorderLayout.NORTH);
        add(cards, BorderLayout.PAGE_START);
        add(center, BorderLayout.CENTER);
        add(controls, BorderLayout.WEST);

        // ===== Events =====
        btnConnect.addActionListener(e -> onConnect());
        btnRegister.addActionListener(e -> onRegister());
        btnLogin.addActionListener(e -> onLogin());
        btnSend.addActionListener(e -> sendLineFromInput());
        tfInput.addActionListener(e -> sendLineFromInput());

        btnPlayers.addActionListener(e -> sendCommand("/players"));
        btnRole.addActionListener(e -> sendCommand("/role"));
        btnStart.addActionListener(e -> sendCommand("/start"));
        btnDay.addActionListener(e -> sendCommand("/day"));
        btnEndDay.addActionListener(e -> sendCommand("/endday"));
        btnNight.addActionListener(e -> sendCommand("/night"));
        btnEndNight.addActionListener(e -> sendCommand("/endnight"));

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                cleanup();
                dispose();
                System.exit(0);
            }
        });

        // Initial state
        setAuthEnabled(false);
        setChatEnabled(false);
        setGameControlsEnabled(false);

        pack();
        setLocationRelativeTo(null);
    }

    // ===== UI helpers =====
    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu view = new JMenu("View");
        JCheckBoxMenuItem dark = new JCheckBoxMenuItem("Dark mode");
        dark.addActionListener(e -> {
            boolean on = dark.isSelected();
            if (on) FlatDarkLaf.setup(); else FlatLightLaf.setup();
            SwingUtilities.updateComponentTreeUI(this);
            pack();
        });
        view.add(dark);
        mb.add(view);
        return mb;
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        return g;
    }

    private static TitledBorder cardBorder(String title) {
        TitledBorder b = BorderFactory.createTitledBorder(title);
        b.setTitleFont(b.getTitleFont().deriveFont(Font.BOLD));
        return b;
    }

    private void setStatus(boolean ok) {
        lblConnStatus.setText(ok ? "Connected" : "Disconnected");
        lblConnStatus.setBackground(ok ? new Color(0xE7F5EE) : new Color(0xF5E7E7));
        lblConnStatus.setForeground(ok ? new Color(0x007E4F) : new Color(0x9B1C1C));
    }

    private void setAuthEnabled(boolean enabled) {
        tfUsername.setEnabled(enabled);
        pfPassword.setEnabled(enabled);
        btnRegister.setEnabled(enabled);
        btnLogin.setEnabled(enabled);
    }
    private void setChatEnabled(boolean enabled) {
        tfInput.setEnabled(enabled);
        btnSend.setEnabled(enabled);
    }
    private void setGameControlsEnabled(boolean enabled) {
        btnPlayers.setEnabled(enabled);
        btnRole.setEnabled(enabled);
        btnStart.setEnabled(enabled);
        btnDay.setEnabled(enabled);
        btnEndDay.setEnabled(enabled);
        btnNight.setEnabled(enabled);
        btnEndNight.setEnabled(enabled);
    }

    // ===== Actions =====
    private void onConnect() {
        if (connected) { appendLine("[CLIENT] Already connected."); return; }
        String host = tfHost.getText().trim();
        int port;
        try { port = Integer.parseInt(tfPort.getText().trim()); }
        catch (NumberFormatException ex) { JOptionPane.showMessageDialog(this, "Port must be a number.", "Error", JOptionPane.ERROR_MESSAGE); return; }
        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            connected = true;
            setStatus(true);
            appendLine("[CLIENT] Connected to " + host + ":" + port);

            readerThread = new Thread(this::readerLoop, "client-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            setAuthEnabled(true);
            setChatEnabled(false);
            setGameControlsEnabled(false);
        } catch (IOException ex) {
            appendLine("[CLIENT] Connect failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Connect failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onRegister() {
        if (!connected) { JOptionPane.showMessageDialog(this, "Please connect first.", "Info", JOptionPane.INFORMATION_MESSAGE); return; }
        String u = tfUsername.getText().trim();
        String p = new String(pfPassword.getPassword());
        if (u.isEmpty() || p.isEmpty()) { JOptionPane.showMessageDialog(this, "Username & password are required.", "Warning", JOptionPane.WARNING_MESSAGE); return; }
        sendLine("/register " + u + " " + p);
    }

    private void onLogin() {
        if (!connected) { JOptionPane.showMessageDialog(this, "Please connect first.", "Info", JOptionPane.INFORMATION_MESSAGE); return; }
        String u = tfUsername.getText().trim();
        String p = new String(pfPassword.getPassword());
        if (u.isEmpty() || p.isEmpty()) { JOptionPane.showMessageDialog(this, "Username & password are required.", "Warning", JOptionPane.WARNING_MESSAGE); return; }
        sendLine("/login " + u + " " + p);
    }

    private void sendLineFromInput() {
        String msg = tfInput.getText().trim();
        if (msg.isEmpty()) return;
        sendLine(msg);
        tfInput.setText("");
    }

    private void sendCommand(String cmd) { sendLine(cmd); }

    private void sendLine(String line) {
        if (!connected || out == null) { appendLine("[CLIENT] Not connected."); return; }
        out.println(line);
    }

    private void readerLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                final String msg = line;
                SwingUtilities.invokeLater(() -> onServerLine(msg));
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> appendLine("[CLIENT] Disconnected: " + e.getMessage()));
        } finally {
            SwingUtilities.invokeLater(() -> {
                connected = false;
                authenticated = false;
                setStatus(false);
                setAuthEnabled(false);
                setChatEnabled(false);
                setGameControlsEnabled(false);
            });
            cleanup();
        }
    }

    private void onServerLine(String line) {
        appendLine(line);
        if (line.contains("[AUTH_OK]")) {
            authenticated = true;
            setAuthEnabled(false);
            setChatEnabled(true);
            setGameControlsEnabled(true);
        } else if (line.contains("[AUTH_FAIL]")) {
            authenticated = false;
            setAuthEnabled(true);
            setChatEnabled(false);
            setGameControlsEnabled(false);
            JOptionPane.showMessageDialog(this, line, "Login/Register Failed", JOptionPane.WARNING_MESSAGE);
        } else if (line.contains("[REGISTER_OK]")) {
            JOptionPane.showMessageDialog(this, line, "Register", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void appendLine(String text) {
        taDisplay.append(text + "\n");
        taDisplay.setCaretPosition(taDisplay.getDocument().getLength());
    }

    private void cleanup() {
        try { if (out != null) out.flush(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
        try { if (readerThread != null && readerThread.isAlive()) readerThread.interrupt(); } catch (Exception ignored) {}
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = 12345;
        if (args != null && args.length >= 1 && args[0] != null && !args[0].isBlank()) host = args[0];
        if (args != null && args.length >= 2) { try { port = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {} }

        final String host0 = host;
        final int port0 = port;
        SwingUtilities.invokeLater(() -> new SimpleSwingClient(host0, port0).setVisible(true));
    }
}
