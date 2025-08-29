// File: src/main/java/com/mafiaonline/clientfx/FxClientApp.java
package com.mafiaonline.clientfx;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FxClientApp extends Application {
    // ===== App State =====
    private final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private boolean darkTheme = true;
    private boolean authenticated = false;

    // Countdown state
    private Timeline timer;
    private int remainingSec = 0;
    private static final int DEFAULT_DAY_SEC = 300;   // 5 phút
    private static final int DEFAULT_NIGHT_SEC = 120; // 2 phút

    // Networking
    private NetClient net;

    // Root containers
    private StackPane root;
    private VBox authView;        // login/register first
    private BorderPane gameView;  // chat/game UI after auth

    // ===== Auth View Controls =====
    private TextField authHost;
    private TextField authPort;
    private TextField authUser;
    private PasswordField authPass;
    private Button btnRegister;
    private Button btnLogin;
    private Label authStatus;
    private TextArea authLog;

    // ===== Game View Controls =====
    private TextField hostField;
    private TextField portField;
    private TextField userField;
    private Button startBtn;
    private Button connectBtn;
    private Button disconnectBtn;
    private ToggleButton themeToggle;

    private ListView<UiMessage> chatList;
    private ListView<String> playerList;
    private TextField inputField;
    private Button sendBtn;
    private MenuButton quickBtn;
    private Label statusLbl;
    private Label phaseLbl;
    private Label timerLbl;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Mafia Online — JavaFX Client");

        // Build both views
        authView  = buildAuthView();
        gameView  = buildGameView();

        root = new StackPane(authView); // show auth first
        applyTheme(root);

        Scene scene = new Scene(root, 1100, 700);
        stage.setScene(scene);
        stage.show();
    }

    // =================== AUTH VIEW ===================
    private VBox buildAuthView() {
        Label title = new Label("Welcome to Mafia Online");
        title.setFont(Font.font("SF Pro Display", 26));
        title.setStyle("-fx-font-weight: 900; -fx-text-fill: white;");

        authHost = pillTextField("Host (e.g. localhost)", 240); authHost.setText("localhost");
        authPort = pillTextField("Port", 160); authPort.setText("12345");
        authUser = pillTextField("Username", 240); authUser.setText("player" + (int)(Math.random()*1000));
        authPass = new PasswordField();
        authPass.setPromptText("Password");
        authPass.setPrefWidth(240);
        authPass.setStyle(pillStyle());

        btnRegister = primaryButton("Register");
        btnLogin    = accentButton("Login");

        authStatus = new Label("Fill in and choose Register/Login");
        authStatus.setStyle("-fx-text-fill: rgba(255,255,255,0.85);");

        authLog = new TextArea();
        authLog.setEditable(false);
        authLog.setWrapText(true);
        authLog.setPrefRowCount(6);
        authLog.setStyle(darkTextAreaStyle()); // nền đen, chữ trắng
        VBox.setVgrow(authLog, Priority.NEVER);

        GridPane form = new GridPane();
        form.setHgap(12); form.setVgap(12);
        form.addRow(0, new Label("Host"), authHost);
        form.addRow(1, new Label("Port"), authPort);
        form.addRow(2, new Label("Username"), authUser);
        form.addRow(3, new Label("Password"), authPass);
        form.getChildren().forEach(n -> { if (n instanceof Label l) l.setStyle("-fx-text-fill: rgba(255,255,255,0.9); -fx-font-weight: 600;"); });

        HBox buttons = new HBox(12, btnRegister, btnLogin);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox card = card(new VBox(16, title, form, buttons, authStatus, authLog));
        VBox.setVgrow(card, Priority.NEVER);

        VBox container = new VBox(card);
        container.setPadding(new Insets(40));
        container.setAlignment(Pos.TOP_CENTER);

        // Actions
        btnRegister.setOnAction(e -> doAuth(true));
        btnLogin.setOnAction(e -> doAuth(false));
        authPass.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) doAuth(false); });

        return container;
    }

    private void doAuth(boolean register) {
        String host = authHost.getText().trim();
        String user = authUser.getText().trim();
        String pass = authPass.getText();
        int port;
        try { port = Integer.parseInt(authPort.getText().trim()); }
        catch (Exception ex) { authToast("Port không hợp lệ"); return; }
        if (user.isEmpty() || pass.isEmpty()) { authToast("Thiếu username/password"); return; }

        ensureConnected(host, port);
        if (net == null || !net.isConnected()) { authToast("Kết nối server thất bại"); return; }

        String cmd = (register ? "/register " : "/login ") + user + " " + pass;
        net.send(cmd);
        authToast((register ? "Register" : "Login") + " sent — chờ phản hồi…");
    }

    private void ensureConnected(String host, int port) {
        try {
            if (net == null || !net.isConnected()) {
                net = new NetClient(host, port, this::onServerLine, this::onDisconnect);
                net.connect();
            }
        } catch (Exception e) {
            authToast("Connect error: " + e.getMessage());
        }
    }

    private void authToast(String s) {
        Platform.runLater(() -> {
            authStatus.setText(s);
            authLog.appendText("[" + TS.format(LocalDateTime.now()) + "] " + s + "\n");
        });
    }

    // =================== GAME (CHAT) VIEW ===================
    private BorderPane buildGameView() {
        // Top AppBar
        Label title = new Label("Mafia Online");
        title.setFont(Font.font("SF Pro Display", 20));
        title.setStyle("-fx-font-weight: 800; -fx-text-fill: white;");

        hostField = pillTextField("Host", 140);
        portField = pillTextField("Port", 96);
        userField = pillTextField("Username", 160);

        startBtn = accentButton("Start Game");
        startBtn.setOnAction(e -> {
            if (net == null || !net.isConnected()) { toast("Chưa kết nối server"); return; }
            net.send("/start");
            toast("Gửi /start");
        });

        connectBtn = primaryButton("Connect");
        disconnectBtn = dangerButton("Disconnect");
        disconnectBtn.setDisable(true);

        themeToggle = new ToggleButton("☾");
        themeToggle.setSelected(true);
        themeToggle.setOnAction(e -> toggleTheme());
        themeToggle.setStyle("-fx-background-radius: 999; -fx-padding: 6 10; -fx-background-color: rgba(255,255,255,0.06); -fx-text-fill: white;");

        HBox appBar = new HBox(10,
                title, spacer(),
                new Label("Host:"), hostField,
                new Label("Port:"), portField,
                new Label("User:"), userField,
                themeToggle, startBtn, connectBtn, disconnectBtn
        );
        appBar.setAlignment(Pos.CENTER_LEFT);
        appBar.setPadding(new Insets(12, 16, 8, 16));
        appBar.getChildren().forEach(n -> { if (n instanceof Label l) l.setStyle("-fx-text-fill: rgba(255,255,255,0.9);"); });

        // Left — Players
        Label playersLbl = new Label("Players");
        playersLbl.setStyle("-fx-text-fill: white; -fx-font-weight: 700;");
        playerList = new ListView<>();
        playerList.setPlaceholder(new Label("No players yet"));
        playerList.setCellFactory(lv -> new PlayerCell());
        VBox left = card(new VBox(8, playersLbl, playerList));
        VBox.setVgrow(playerList, Priority.ALWAYS);
        left.setPrefWidth(240);

        // Center — Chat
        chatList = new ListView<>();
        chatList.setCellFactory(lv -> new MessageCell());
        chatList.setFocusTraversable(false);
        // Nền ListView chat tối
        chatList.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-control-inner-background: #0b0f19;" +
                "-fx-control-inner-background-alt: #0b0f19;" +
                "-fx-text-fill: white;"
        );
        Label placeholder = new Label("No messages");
        placeholder.setStyle("-fx-text-fill: rgba(255,255,255,0.7);");
        chatList.setPlaceholder(placeholder);

        VBox centerCard = card(new VBox(chatList));
        VBox.setVgrow(chatList, Priority.ALWAYS);

        // Bottom — Input bar
        inputField = pillTextField("Type message…  ('quit' to exit)", 0);
        HBox.setHgrow(inputField, Priority.ALWAYS);
        sendBtn = accentButton("Send");
        sendBtn.setDisable(true);

        MenuItem miClear = new MenuItem("Clear chat");
        miClear.setOnAction(e -> chatList.getItems().clear());
        quickBtn = new MenuButton("⋯", null, miClear);
        quickBtn.setStyle("-fx-background-radius: 12; -fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-padding: 8 14; -fx-font-size: 16px;");

        HBox inputBar = new HBox(10, inputField, sendBtn, quickBtn);
        inputBar.setPadding(new Insets(10, 12, 12, 12));

        // Status bar (phase + timer + status text)
        phaseLbl = new Label("—");
        phaseLbl.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-padding: 6 10; -fx-background-radius: 999; -fx-font-weight: 700;");
        timerLbl = new Label("00:00");
        timerLbl.setStyle("-fx-text-fill: rgba(255,255,255,0.9); -fx-font-weight: 800; -fx-padding: 0 8;");

        HBox phaseBox = new HBox(8, phaseLbl, timerLbl);
        phaseBox.setAlignment(Pos.CENTER_RIGHT);

        statusLbl = new Label("Disconnected");
        statusLbl.setStyle("-fx-text-fill: rgba(255,255,255,0.85);");

        Region spacerStatus = new Region(); HBox.setHgrow(spacerStatus, Priority.ALWAYS);
        HBox statusBar = new HBox(statusLbl, spacerStatus, phaseBox);
        statusBar.setPadding(new Insets(0, 12, 12, 12));

        // Assemble layout
        BorderPane pane = new BorderPane();
        pane.setTop(appBar);
        pane.setLeft(left);
        pane.setCenter(centerCard);
        pane.setBottom(new VBox(inputBar, statusBar));

        // Actions
        connectBtn.setOnAction(e -> connectGame());
        disconnectBtn.setOnAction(e -> disconnect());
        sendBtn.setOnAction(e -> send());
        inputField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) { e.consume(); send(); } });

        return pane;
    }

    private void connectGame() {
        String host = hostField.getText().trim();
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); } catch (Exception ex) { toast("Port không hợp lệ"); return; }
        String user = userField.getText().trim();
        if (user.isEmpty()) { toast("Username không được rỗng"); return; }

        try {
            if (net == null || !net.isConnected()) {
                net = new NetClient(host, port, this::onServerLine, this::onDisconnect);
                net.connect();
            }
            sendBtn.setDisable(false);
            disconnectBtn.setDisable(false);
            statusLbl.setText("Connected to %s:%d".formatted(host, port));
            toast("Connected");
            inputField.requestFocus();
            ensurePlayer(user);
        } catch (Exception e) {
            appendError(e.getMessage());
            statusLbl.setText("Disconnected");
        }
    }

    private void disconnect() {
        if (net != null) net.close();
        net = null;
        sendBtn.setDisable(true);
        disconnectBtn.setDisable(true);
        statusLbl.setText("Disconnected");
        stopCountdown();
        if (phaseLbl != null) phaseLbl.setText("—");
        toast("Disconnected");
    }

    private void send() {
        String text = inputField.getText();
        if (text == null || text.isBlank()) return;
        if (net == null || !net.isConnected()) { toast("Chưa kết nối server"); return; }
        net.send(text);
        addMine(userField.getText().trim(), text);
        inputField.clear();
        if ("quit".equalsIgnoreCase(text.trim())) {
            sendBtn.setDisable(true);
        }
    }

    // =================== LINE HANDLERS ===================
    private void onServerLine(String raw) {
        final String line = raw == null ? "" : raw;
        if (!authenticated) {
            String lc = line.toLowerCase(Locale.ROOT);
            if (looksLikeAuthSuccess(lc)) {
                authenticated = true;
                Platform.runLater(() -> {
                    authToast("Auth OK — vào game UI");
                    // Nhét sẵn thông tin vào game bar
                    hostField.setText(authHost.getText());
                    portField.setText(authPort.getText());
                    userField.setText(authUser.getText());
                    // Đổi view
                    root.getChildren().setAll(gameView);
                    applyTheme(root);
                    // Kích hoạt trạng thái
                    sendBtn.setDisable(false);
                    disconnectBtn.setDisable(false);
                    ensurePlayer(authUser.getText().trim());
                    statusLbl.setText("Connected to %s:%s".formatted(authHost.getText(), authPort.getText()));
                });
                return;
            }
            if (looksLikeAuthFail(lc)) { authToast("Auth failed: " + line); return; }
            // log các dòng khác vào auth log
            authToast(line);
            return;
        }
        // Đã vào GAME UI
        Platform.runLater(() -> addServer(line));
    }

    private void onDisconnect(String reason) {
        Platform.runLater(() -> {
            stopCountdown();
            if (phaseLbl != null) phaseLbl.setText("—");
            if (!authenticated) authToast("Disconnected: " + reason);
            else {
                appendSystem("Disconnected: " + reason);
                disconnect();
            }
        });
    }

    // =================== UI Helpers ===================
    private TextField pillTextField(String prompt, double prefWidth) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        if (prefWidth > 0) tf.setPrefWidth(prefWidth);
        tf.setStyle(pillStyle());
        return tf;
    }

    private String pillStyle() {
        return "-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 999; -fx-text-fill: white; " +
               "-fx-prompt-text-fill: rgba(255,255,255,0.55); -fx-padding: 9 16; -fx-border-radius: 999; -fx-border-color: rgba(255,255,255,0.12);";
    }

    private static boolean looksLikeAuthSuccess(String lc) {
        return lc.contains("auth_ok")
            || lc.contains("login ok")
            || lc.contains("logged in")
            || lc.contains("register ok")
            || lc.contains("registered")
            || lc.contains("đăng nhập thành công")
            || lc.contains("dang nhap thanh cong")
            || lc.contains("đăng ký thành công")
            || lc.contains("dang ky thanh cong");
    }

    private static boolean looksLikeAuthFail(String lc) {
        return lc.contains("auth_fail")
            || lc.contains("error")
            || lc.contains("fail")
            || lc.contains("invalid")
            || lc.contains("wrong");
    }

    // TextArea nền đen – chữ trắng
    private String darkTextAreaStyle() {
        return String.join("",
            "-fx-text-fill: white;",
            "-fx-font-family: 'SF Mono','Consolas','Menlo',monospace;",
            "-fx-font-size: 12px;",
            "-fx-control-inner-background: #0b0f19;",
            "-fx-control-inner-background-alt: #0b0f19;",
            "-fx-background-color: rgba(255,255,255,0.05);",
            "-fx-background-radius: 12;",
            "-fx-border-radius: 12;",
            "-fx-border-color: rgba(255,255,255,0.12);",
            "-fx-highlight-fill: #1f2937;",
            "-fx-highlight-text-fill: white;",
            "-fx-caret-color: white;"
        );
    }

    private VBox card(Region content) {
        content.setPadding(new Insets(14));
        VBox wrap = new VBox(content);
        wrap.setPadding(new Insets(10));
        wrap.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 16; " +
                      "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 22, 0.20, 0, 8);");
        return wrap;
    }

    private Button primaryButton(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: linear-gradient(to bottom right,#22c55e,#16a34a); -fx-text-fill:#0b0f19; -fx-font-weight: 800; -fx-background-radius: 14; -fx-padding: 9 18; -fx-effect: dropshadow(gaussian, rgba(34,197,94,0.35), 16, 0.35, 0, 6);");
        return b;
    }

    private Button accentButton(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: linear-gradient(to bottom right,#4f46e5,#4338ca); -fx-text-fill:white; -fx-font-weight: 800; -fx-background-radius: 14; -fx-padding: 9 20; -fx-letter-spacing:0.3px; -fx-effect: dropshadow(gaussian, rgba(99,102,241,0.45), 18, 0.35, 0, 8);");
        return b;
    }

    private Button dangerButton(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: linear-gradient(to bottom right,#f43f5e,#e11d48); -fx-text-fill:white; -fx-font-weight: 800; -fx-background-radius: 14; -fx-padding: 9 18; -fx-effect: dropshadow(gaussian, rgba(244,63,94,0.40), 18, 0.35, 0, 8);");
        return b;
    }

    private Region spacer() { Region r = new Region(); HBox.setHgrow(r, Priority.ALWAYS); return r; }

    private void applyTheme(Pane target) {
        if (darkTheme)
            target.setStyle("-fx-background-color: linear-gradient(to bottom right,#0b1020,#111827);");
        else
            target.setStyle("-fx-background-color: linear-gradient(to bottom right,#e5e7eb,#f8fafc);");
    }

    private void toggleTheme() { darkTheme = !darkTheme; themeToggle.setText(darkTheme ? "☾" : "☀"); applyTheme(root); }

    // =================== Countdown helpers ===================
    private void startCountdown(int seconds, String phase) {
        if (timer != null) timer.stop();
        remainingSec = Math.max(0, seconds);
        if (phaseLbl != null) phaseLbl.setText(phase);
        updateTimerLabel();

        timer = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
            remainingSec = Math.max(remainingSec - 1, 0);
            updateTimerLabel();
            if (remainingSec <= 0) timer.stop();
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void stopCountdown() {
        if (timer != null) timer.stop();
        remainingSec = 0;
        updateTimerLabel();
    }

    private void updateTimerLabel() {
        int m = remainingSec / 60, s = remainingSec % 60;
        if (timerLbl != null) timerLbl.setText(String.format("%02d:%02d", m, s));
    }

    private int parseSecondsFromText(String s, int fallback) {
        if (s == null) return fallback;
        String lc = s.toLowerCase(Locale.ROOT);
        // Ưu tiên phút
        Matcher mp = Pattern.compile("(\\d+)\\s*(phút|min)").matcher(lc);
        if (mp.find()) return Integer.parseInt(mp.group(1)) * 60;
        // Giây
        Matcher ms = Pattern.compile("(\\d+)\\s*(giây|s|sec|seconds?)").matcher(lc);
        if (ms.find()) return Integer.parseInt(ms.group(1));
        return fallback;
    }

    // =================== Chat helpers ===================
    private void addMine(String user, String text) {
        chatList.getItems().add(new UiMessage(user, text, true));
        animateList();
    }

    // Hiển thị chat + gameplay; ẩn join/leave & auth; khởi động đếm ngược khi thấy DAY/NIGHT
    private void addServer(String text) {
        String s = (text == null) ? "" : text.trim();
        // Bỏ [HH:mm:ss] nếu có
        if (s.startsWith("[")) {
            int i = s.indexOf(']');
            if (i > 0 && i <= 12) s = s.substring(i + 1).trim();
        }
        String lc = s.toLowerCase(Locale.ROOT);

        // 1) Ẩn: join/leave & auth noise
        if (lc.contains("joined") || lc.contains("has joined")
                || lc.contains("left") || lc.contains("has left")
                || lc.contains("đã tham gia") || lc.contains("da tham gia")
                || lc.contains("đã rời") || lc.contains("da roi")
                || lc.startsWith("[auth_") || lc.contains("auth ok") || lc.contains("auth fail")
                || lc.contains("login ok") || lc.contains("register ok") || lc.contains("invalid password")) {
            return;
        }

        // 2) Bắt tín hiệu DAY/NIGHT để hiển thị phase + timer
        if (lc.contains("[day]") || lc.startsWith("day") || lc.contains("ban ngày") || lc.contains("bắt đầu ngày") || lc.contains("bat dau ngay")) {
            int sec = parseSecondsFromText(s, DEFAULT_DAY_SEC);
            startCountdown(sec, "DAY");
            appendSystem(s);
            animateList();
            return;
        }
        if (lc.contains("[night]") || lc.startsWith("night") || lc.contains("ban đêm") || lc.contains("bat dau dem") || lc.contains("bắt đầu đêm")) {
            int sec = parseSecondsFromText(s, DEFAULT_NIGHT_SEC);
            startCountdown(sec, "NIGHT");
            appendSystem(s);
            animateList();
            return;
        }
        if (lc.contains("end of day") || lc.contains("hết ngày") || lc.contains("ket thuc ngay")) {
            stopCountdown();
            if (phaseLbl != null) phaseLbl.setText("—");
            appendSystem(s);
            animateList();
            return;
        }
        if (lc.contains("end of night") || lc.contains("hết đêm") || lc.contains("ket thuc dem")) {
            stopCountdown();
            if (phaseLbl != null) phaseLbl.setText("—");
            appendSystem(s);
            animateList();
            return;
        }

        // 3) Chat thật: "username: message"
        int colon = s.indexOf(':');
        if (colon > 0 && colon < 32) {
            String who = s.substring(0, colon).trim();
            String msg = s.substring(colon + 1).trim();

            // Bỏ echo của chính mình
            String me = "";
            if (userField != null && userField.getText() != null) me = userField.getText().trim();
            if ((me == null || me.isEmpty()) && authUser != null && authUser.getText() != null) me = authUser.getText().trim();
            if (me != null && !me.isEmpty() && who.equalsIgnoreCase(me)) return;

            ensurePlayer(who);
            chatList.getItems().add(new UiMessage(who, msg, false));
            animateList();
            return;
        }

        // 4) Các thông điệp gameplay khác (vote, lynch, killed...) => SYSTEM
        appendSystem(s);
        animateList();
    }

    private String extractName(String s) {
        int lb = s.indexOf('['), rb = s.indexOf(']');
        if (lb >= 0 && rb > lb) return s.substring(lb+1, rb).trim();
        String[] p = s.split("\\s+");
        return p.length > 0 ? p[0] : s;
    }

    private void animateList() {
        int idx = chatList.getItems().size() - 1; if (idx < 0) return;
        chatList.scrollTo(idx);
        FadeTransition ft = new FadeTransition(Duration.millis(220), chatList);
        ft.setFromValue(0.92); ft.setToValue(1.0); ft.play();
    }

    private void appendSystem(String s) { chatList.getItems().add(UiMessage.system(s)); animateList(); }
    private void appendError(String s)  { chatList.getItems().add(UiMessage.error(s));  animateList(); }

    private void ensurePlayer(String user) {
        if (user == null || user.isBlank()) return;
        if (!playerList.getItems().contains(user)) playerList.getItems().add(user);
    }

    private void toast(String msg) { statusLbl.setText(msg); }

    // =================== Models & Cells ===================
    private static class UiMessage {
        final String who; final String text; final boolean mine; final LocalDateTime ts = LocalDateTime.now(); final MsgKind kind;
        enum MsgKind { NORMAL, SYSTEM, ERROR }
        UiMessage(String who, String text, boolean mine) { this(who, text, mine, MsgKind.NORMAL); }
        UiMessage(String who, String text, boolean mine, MsgKind kind) { this.who = who; this.text = text; this.mine = mine; this.kind = kind; }
        static UiMessage system(String s) { return new UiMessage("system", s, false, MsgKind.SYSTEM); }
        static UiMessage error(String s) { return new UiMessage("error", s, false, MsgKind.ERROR); }
    }

    private class MessageCell extends ListCell<UiMessage> {
        private final HBox root = new HBox();
        private final VBox bubble = new VBox();
        private final Label name = new Label();
        private final Label content = new Label();
        private final Label time = new Label();

        MessageCell() {
            super();
            bubble.setPadding(new Insets(8, 12, 8, 12));
            bubble.setMaxWidth(700);
            bubble.setSpacing(4);
            bubble.setStyle("-fx-background-radius:16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 12, 0.2, 0, 4);");
            name.setStyle("-fx-font-weight:700; -fx-text-fill: rgba(255,255,255,0.9);");
            content.setWrapText(true); content.setStyle("-fx-text-fill: white;");
            time.setStyle("-fx-text-fill: rgba(255,255,255,0.7); -fx-font-size: 11px;");
            bubble.getChildren().addAll(name, content, time);
            root.getChildren().add(bubble);
            root.setPadding(new Insets(6,10,6,10));
        }

        @Override protected void updateItem(UiMessage m, boolean empty) {
            super.updateItem(m, empty);
            if (empty || m == null) { setGraphic(null); return; }
            name.setText(m.who); content.setText(m.text); time.setText(TS.format(m.ts));
            root.setAlignment(m.mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            switch (m.kind) {
                case SYSTEM -> bubble.setStyle(
                        "-fx-background-radius:16; " +
                        "-fx-background-color: rgba(14,165,233,0.18); " +
                        "-fx-effect: dropshadow(gaussian, rgba(14,165,233,0.25), 10, 0.22, 0, 4);"
                );
                case ERROR  -> bubble.setStyle(
                        "-fx-background-radius:16; " +
                        "-fx-background-color: rgba(244,63,94,0.22); " +
                        "-fx-effect: dropshadow(gaussian, rgba(244,63,94,0.25), 10, 0.22, 0, 4);"
                );
                default -> {
                    if (m.mine) {
                        bubble.setStyle(
                                "-fx-background-radius:16; " +
                                "-fx-background-color: linear-gradient(to bottom right,#4f46e5,#4338ca); " +
                                "-fx-effect: dropshadow(gaussian, rgba(99,102,241,0.35), 14, 0.3, 0, 6);"
                        );
                    } else {
                        bubble.setStyle(
                                "-fx-background-radius:16; " +
                                "-fx-background-color: rgba(255,255,255,0.06);"
                        );
                    }
                }
            }
            setGraphic(root);
        }
    }

    private static class PlayerCell extends ListCell<String> {
        private final HBox root = new HBox(10);
        private final Circle avatar = new Circle(10);
        private final Label name = new Label();
        private static final Random RNG = new Random();
        PlayerCell() {
            root.setAlignment(Pos.CENTER_LEFT);
            root.setPadding(new Insets(6,8,6,8));
            name.setStyle("-fx-text-fill: white; -fx-font-weight: 600;");
            root.getChildren().addAll(avatar, name);
        }
        @Override protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); return; }
            name.setText(item);
            avatar.setFill(Color.hsb(RNG.nextDouble()*360, 0.6, 1.0));
            setGraphic(root);
        }
    }

    public static void main(String[] args) { launch(args); }
}
