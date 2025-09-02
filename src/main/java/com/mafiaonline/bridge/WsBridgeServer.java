package com.mafiaonline.bridge;

import com.mafiaonline.common.JsonUtil;
import com.mafiaonline.common.Message;
import com.mafiaonline.common.MessageType;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WS <-> TCP Bridge (1 WS : 1 TCP, không chia sẻ, không broadcast chéo)
 * - WS -> TCP:
 *    * Nếu JSON {type:"CHAT", content:"..."}: đẩy content (lệnh /... hay chat thuần)
 *    * START_GAME -> "/start", VOTE -> "/vote <target>", LEAVE -> "/quit"
 *    * Nếu không parse được: đẩy raw
 * - TCP -> WS:
 *    * Mọi dòng đóng gói thành JSON SYSTEM (tự build, không phụ thuộc toJson)
 *    * Khi thấy [AUTH_OK] -> phát JOIN cho ws hiện tại (1 lần)
 *    * Khi thấy "👤 <name> đã tham gia ..." -> phát JOIN, tránh trùng với user vừa AUTH_OK
 */
public class WsBridgeServer extends WebSocketServer {

    private final String path;
    private final String tcpHost;
    private final int tcpPort;

    public WsBridgeServer(InetSocketAddress wsAddr, String path, String tcpHost, int tcpPort) {
        super(wsAddr);
        this.path = (path == null || path.isBlank()) ? "/ws" : path;
        this.tcpHost = (tcpHost == null || tcpHost.isBlank()) ? "127.0.0.1" : tcpHost;
        this.tcpPort = tcpPort;
    }

    /** Trạng thái cho từng WS connection */
    private static class ConnState {
        Socket tcp;
        PrintWriter toTcp;
        BufferedReader fromTcp;
        Thread pumpThread;
        volatile String lastLoginUser = null; // user thấy trong /login <user> ...
        volatile String authedUser = null;    // user đã xác thực (đã bắn JOIN)
        volatile boolean closing = false;
    }

    private final Map<WebSocket, ConnState> states = new ConcurrentHashMap<>();

    /* ===================== WebSocketServer callbacks ===================== */

    @Override
    public void onStart() {
        // Ping/Pong tự động, đề phòng treo
        setConnectionLostTimeout(30);
        System.out.println("[WS] Bridge listening at ws://" + getAddress().getHostString() + ":" + getAddress().getPort() + path);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake hs) {
        String res = hs.getResourceDescriptor(); // ví dụ: "/ws?token=..."
        if (!acceptPath(res)) {
            conn.close(1008, "Invalid path. Use " + path);
            return;
        }

        ConnState st = new ConnState();
        states.put(conn, st);
        try {
            st.tcp = new Socket(tcpHost, tcpPort);
            st.toTcp = new PrintWriter(new OutputStreamWriter(st.tcp.getOutputStream(), StandardCharsets.UTF_8), true);
            st.fromTcp = new BufferedReader(new InputStreamReader(st.tcp.getInputStream(), StandardCharsets.UTF_8));

            st.pumpThread = new Thread(() -> pumpTcpToWs(conn, st), "pump-" + conn.hashCode());
            st.pumpThread.setDaemon(true);
            st.pumpThread.start();

            sendSystem(conn, "Connected to TCP " + tcpHost + ":" + tcpPort);
            System.out.println("[WS] open  " + conn.getRemoteSocketAddress());
        } catch (IOException e) {
            sendSystem(conn, "❌ Cannot connect TCP: " + e.getMessage());
            try { conn.close(1011, "tcp connect fail"); } catch (Exception ignore) {}
            closeState(st);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String raw) {
        ConnState st = states.get(conn);
        if (st == null || st.toTcp == null) return;

        // 1) cố parse chuẩn theo common.Message
        Message m = null;
        try { m = JsonUtil.fromJson(raw); } catch (Exception ignore) {}

        if (m != null && m.getType() != null) {
            routeMessage(st, m);
            return;
        }

        // 2) fallback regex nếu trông như JSON
        if (raw != null && !raw.isEmpty() && raw.charAt(0) == '{') {
            String type = extractField(raw, "type");
            String content = extractField(raw, "content");
            if (type != null) {
                String t = type.toUpperCase();
                if ("CHAT".equals(t)) {
                    if (content != null) {
                        if (content.startsWith("/login ")) {
                            String[] sp = content.split("\\s+", 3);
                            if (sp.length >= 2) st.lastLoginUser = sp[1];
                        }
                        st.toTcp.println(content); // lệnh hoặc chat
                    }
                    return;
                } else if ("START_GAME".equals(t)) {
                    st.toTcp.println("/start"); return;
                } else if ("VOTE".equals(t)) {
                    st.toTcp.println((content == null || content.isBlank()) ? "/vote" : "/vote " + content); return;
                } else if ("LEAVE".equals(t)) {
                    st.toTcp.println("/quit"); return;
                }
                if (content != null && !content.isBlank()) {
                    st.toTcp.println(content);
                    return;
                }
            }
        }

        // 3) còn lại: đẩy raw xuống TCP (server sẽ coi như 1 dòng)
        st.toTcp.println(raw);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ConnState st = states.remove(conn);
        closeState(st);
        System.out.println("[WS] close " + conn.getRemoteSocketAddress() + " code=" + code + " " + reason);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WS] error: " + ex.getMessage());
        ConnState st = (conn != null) ? states.get(conn) : null;
        if (st != null && (st.tcp == null || st.tcp.isClosed())) {
            try { conn.close(1011, "tcp closed"); } catch (Exception ignore) {}
        }
    }

    /* ===================== Core bridging ===================== */

    private void routeMessage(ConnState st, Message m) {
        final String content = m.getContent() == null ? "" : m.getContent().trim();
        final MessageType type = m.getType();

        switch (type) {
            case START_GAME -> st.toTcp.println("/start");
            case VOTE       -> st.toTcp.println(content.isEmpty() ? "/vote" : "/vote " + content);
            case CHAT -> {
                if (content.startsWith("/")) {
                    if (content.startsWith("/login ")) {
                        String[] sp = content.split("\\s+", 3);
                        if (sp.length >= 2) st.lastLoginUser = sp[1];
                    }
                    st.toTcp.println(content);
                } else {
                    st.toTcp.println(content); // chat thuần
                }
            }
            case LEAVE -> st.toTcp.println("/quit");
            default -> {
                if (!content.isEmpty()) st.toTcp.println(content);
                else st.toTcp.println(jsonFromMessage(m)); // echo để khỏi mất dữ liệu
            }
        }
    }

    /** Luồng đọc TCP -> gửi về ĐÚNG WS đang mở (không broadcast) */
    private void pumpTcpToWs(WebSocket conn, ConnState st) {
        String line;
        try {
            while (!st.closing && (line = st.fromTcp.readLine()) != null) {
                // forward as SYSTEM
                sendSystem(conn, line);

                // AUTH_OK -> phát JOIN một lần cho user vừa login
                if (line.contains("[AUTH_OK]")) {
                    if (st.authedUser == null && st.lastLoginUser != null) {
                        st.authedUser = st.lastLoginUser;
                        safeSend(conn, jsonJoin(st.authedUser));
                    }
                }

                // "👤 <name> đã tham gia phòng." -> phát JOIN (tránh trùng với authedUser)
                if (line.startsWith("👤 ")) {
                    String name = extractJoinedName(line);
                    if (name != null && !name.isBlank()) {
                        if (!name.equals(st.authedUser)) { // tránh JOIN trùng
                            safeSend(conn, jsonJoin(name));
                        }
                    }
                }
            }
        } catch (IOException e) {
            sendSystem(conn, "TCP closed: " + e.getMessage());
        } finally {
            try { conn.close(1000, "tcp eof"); } catch (Exception ignore) {}
            closeState(st);
        }
    }

    /* ===================== Helpers & JSON utils ===================== */

    private boolean acceptPath(String resourceDescriptor) {
        // chấp nhận /ws, /ws/, /ws?x=..., /ws/?x=...
        String res = (resourceDescriptor == null) ? "" : resourceDescriptor;
        int q = res.indexOf('?');
        if (q >= 0) res = res.substring(0, q);
        if (res.endsWith("/")) res = res.substring(0, res.length() - 1);
        String p = this.path.endsWith("/") ? this.path.substring(0, this.path.length() - 1) : this.path;
        return res.isEmpty() || res.equals(p); // một số client có thể gửi "" ở handshake, giữ mềm dẻo
    }

    private static final Pattern FIELD_STR = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static String extractField(String json, String key) {
        Matcher ma = FIELD_STR.matcher(json);
        while (ma.find()) {
            if (key.equals(ma.group(1))) {
                String v = ma.group(2);
                return v.replace("\\\"", "\"").replace("\\\\", "\\");
            }
        }
        return null;
    }

    private static String extractJoinedName(String line) {
        // line dạng: "👤 <name> đã tham gia phòng."
        String s = line.substring(2).trim(); // bỏ emoji
        int idx = s.indexOf("đã tham gia");
        if (idx > 0) return s.substring(0, idx).trim();
        return null;
    }

    /* ===== JSON builders (không throws) ===== */

    private static String esc(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    private static String jsonSystem(String content) {
        long ts = System.currentTimeMillis();
        return "{\"type\":\"SYSTEM\",\"sender\":\"server\",\"content\":" + esc(content) + ",\"timestamp\":" + ts + "}";
    }
    private static String jsonJoin(String sender) {
        long ts = System.currentTimeMillis();
        return "{\"type\":\"JOIN\",\"sender\":" + esc(sender) + ",\"content\":\"\",\"timestamp\":" + ts + "}";
    }
    private static String jsonFromMessage(Message m) {
        long ts = (m.getTimestamp() == 0 ? System.currentTimeMillis() : m.getTimestamp());
        String type = (m.getType() != null ? m.getType().name() : "SYSTEM");
        String sender = m.getSender();
        String content = m.getContent();
        return "{\"type\":\"" + type + "\",\"sender\":" + esc(sender) + ",\"content\":" + esc(content) + ",\"timestamp\":" + ts + "}";
    }

    private void sendSystem(WebSocket conn, String text) {
        safeSend(conn, jsonSystem(text));
    }
    private void safeSend(WebSocket conn, String payload) {
        try { conn.send(payload); } catch (Exception ignore) {}
    }

    private void closeState(ConnState st) {
        if (st == null) return;
        st.closing = true;
        try { if (st.fromTcp != null) st.fromTcp.close(); } catch (Exception ignore) {}
        try { if (st.toTcp != null) st.toTcp.close(); } catch (Exception ignore) {}
        try { if (st.tcp != null && !st.tcp.isClosed()) st.tcp.close(); } catch (Exception ignore) {}
        try { if (st.pumpThread != null) st.pumpThread.interrupt(); } catch (Exception ignore) {}
    }

    /* ===== Chạy độc lập (tuỳ chọn) ===== */
    public static void main(String[] args) {
        int wsPort = 8081;
        String wsPath = "/ws";
        String tcpHost = "127.0.0.1";
        int tcpPort = 12345;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ws-port" -> wsPort = Integer.parseInt(args[++i]);
                case "--ws-path" -> wsPath = args[++i];
                case "--tcp-host" -> tcpHost = args[++i];
                case "--tcp-port" -> tcpPort = Integer.parseInt(args[++i]);
            }
        }

        WsBridgeServer s = new WsBridgeServer(new InetSocketAddress("0.0.0.0", wsPort), wsPath, tcpHost, tcpPort);
        s.start();
        System.out.println("[WS] Bridge standalone at ws://localhost:" + wsPort + wsPath + " -> " + tcpHost + ":" + tcpPort);
    }
}
