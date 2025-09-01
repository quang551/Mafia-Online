// File: src/main/java/com/mafiaonline/common/MessageHandler.java
package com.mafiaonline.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MessageHandler (common) — parse JSON -> Message và phát sự kiện (Listener).
 * Dùng chung cho client/server nếu cần.
 *
 * Nâng cấp:
 * - Fallback parse: nếu JSON có "timestamp" là chuỗi ISO-8601, tự chuyển sang epoch millis rồi parse lại.
 * - Nới validate: START_GAME/END_GAME không bắt buộc sender.
 * - Thêm helper handle(MessageType, sender, content).
 */
public class MessageHandler {

    /* ==================== Listener API ==================== */

    public interface Listener {
        default void onJoin(Message m) {}
        default void onLeave(Message m) {}
        default void onChat(Message m) {}
        default void onPrivateChat(Message m) {}
        default void onVote(Message m) {}
        default void onKill(Message m) {}
        default void onHeal(Message m) {}
        default void onInvestigate(Message m) {}
        default void onStartGame(Message m) {}
        default void onEndGame(Message m) {}
        default void onSystem(Message m) {}
        default void onError(Message m) {}
        /** JSON hỏng hoặc parse lỗi */
        default void onMalformed(String json, Exception e) {}
        /** Message không qua được validate (thiếu field/vi phạm policy) */
        default void onValidationFailed(Message m, String reason) {}
        /** Khi gặp type không biết (phòng xa) */
        default void onUnknown(Message m) {}
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void clearListeners() {
        listeners.clear();
    }

    /* ==================== Entry points ==================== */

    /** Nhận JSON, parse và dispatch (có fallback cho timestamp ISO). */
    public void handleMessage(String json) {
        if (json == null) {
            System.out.println("⚠️ JSON null");
            notifyMalformed(null, new NullPointerException("json is null"));
            return;
        }
        try {
            Message message = JsonUtil.fromJson(json);
            handleMessage(message);
        } catch (Exception e1) {
            // Thử fallback: chuyển "timestamp":"...ISO..." -> "timestamp":<millis>
            try {
                String fixed = coerceTimestampToMillis(json);
                if (!fixed.equals(json)) {
                    Message message = JsonUtil.fromJson(fixed);
                    handleMessage(message);
                    return;
                }
            } catch (Exception ignore) {
                // bỏ qua, sẽ báo malformed bên dưới
            }
            System.out.println("⚠️ Lỗi khi xử lý JSON: " + e1.getMessage());
            notifyMalformed(json, e1);
        }
    }

    /** Nhận Message đã parse sẵn, validate và dispatch. */
    public void handleMessage(Message message) {
        if (message == null) {
            notifyMalformed(null, new NullPointerException("message is null"));
            return;
        }
        String validation = validate(message);
        if (validation != null) {
            System.out.println("⚠️ Message không hợp lệ: " + validation);
            notifyValidationFailed(message, validation);
            return;
        }

        switch (message.getType()) {
            case JOIN -> handleJoin(message);
            case LEAVE -> handleLeave(message);
            case CHAT -> handleChat(message);
            case PRIVATE_CHAT -> handlePrivateChat(message);
            case VOTE -> handleVote(message);
            case KILL -> handleKill(message);
            case HEAL -> handleHeal(message);
            case INVESTIGATE -> handleInvestigate(message);
            case START_GAME -> handleStartGame(message);
            case END_GAME -> handleEndGame(message);
            case SYSTEM -> handleSystem(message);
            case ERROR -> handleError(message);
            default -> {
                System.out.println("❓ Unknown message type: " + message.getType());
                notifyUnknown(message);
            }
        }
    }

    /** Helper: tạo & xử lý nhanh một message (server có thể gọi trực tiếp). */
    public void handle(MessageType type, String sender, String content) {
        handleMessage(of(type, sender, content));
    }

    /* ==================== Validation ==================== */

    private String validate(Message m) {
        if (m.getType() == null) return "type is null";

        // Bắt buộc sender cho các type sau (nới lỏng START/END không yêu cầu)
        boolean requireSender = switch (m.getType()) {
            case JOIN, LEAVE, CHAT, PRIVATE_CHAT, VOTE, KILL, HEAL, INVESTIGATE -> true;
            case START_GAME, END_GAME, SYSTEM, ERROR -> false;
        };
        if (requireSender && isBlank(m.getSender())) return "sender is empty";

        String content = m.getContent();
        switch (m.getType()) {
            case CHAT, PRIVATE_CHAT -> {
                if (isBlank(content)) return "content is empty";
                if (content.length() > Protocol.MAX_CHAT_LENGTH) {
                    m.setContent(content.substring(0, Protocol.MAX_CHAT_LENGTH));
                }
            }
            case VOTE, KILL, HEAL, INVESTIGATE -> {
                if (isBlank(content)) return "target(empty) for " + m.getType();
            }
            case JOIN, LEAVE, START_GAME, END_GAME, SYSTEM, ERROR -> { /* no extra checks */ }
        }

        // Tự điền timestamp nếu thiếu/không hợp lệ
        if (m.getTimestamp() == 0L) {
            m.setTimestamp(System.currentTimeMillis());
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /* ==================== Default handlers (console log + callback) ==================== */

    private void handleJoin(Message message) {
        System.out.println("[JOIN] " + message.getSender() + " đã tham gia phòng.");
        notifyJoin(message);
    }

    private void handleLeave(Message message) {
        System.out.println("[LEAVE] " + message.getSender() + " đã rời phòng.");
        notifyLeave(message);
    }

    private void handleChat(Message message) {
        System.out.println("[CHAT] " + message.getSender() + ": " + message.getContent());
        notifyChat(message);
    }

    private void handlePrivateChat(Message message) {
        System.out.println("[PRIVATE CHAT] " + message.getSender() + ": " + message.getContent());
        notifyPrivateChat(message);
    }

    private void handleVote(Message message) {
        System.out.println("[VOTE] " + message.getSender() + " vote " + message.getContent());
        notifyVote(message);
    }

    private void handleKill(Message message) {
        System.out.println("[KILL] " + message.getSender() + " -> " + message.getContent());
        notifyKill(message);
    }

    private void handleHeal(Message message) {
        System.out.println("[HEAL] " + message.getSender() + " -> " + message.getContent());
        notifyHeal(message);
    }

    private void handleInvestigate(Message message) {
        System.out.println("[INVESTIGATE] " + message.getSender() + " -> " + message.getContent());
        notifyInvestigate(message);
    }

    private void handleStartGame(Message message) {
        System.out.println("[START] Game bắt đầu!" + (isBlank(message.getSender()) ? "" : (" by " + message.getSender())));
        notifyStartGame(message);
    }

    private void handleEndGame(Message message) {
        System.out.println("[END] Game kết thúc!" + (isBlank(message.getSender()) ? "" : (" by " + message.getSender())));
        notifyEndGame(message);
    }

    private void handleSystem(Message message) {
        System.out.println("[SYSTEM] " + message.getContent());
        notifySystem(message);
    }

    private void handleError(Message message) {
        System.out.println("[ERROR] " + message.getContent());
        notifyError(message);
    }

    /* ==================== Notify helpers ==================== */

    private void notifyJoin(Message m) { for (Listener l : listeners) l.onJoin(m); }
    private void notifyLeave(Message m) { for (Listener l : listeners) l.onLeave(m); }
    private void notifyChat(Message m) { for (Listener l : listeners) l.onChat(m); }
    private void notifyPrivateChat(Message m) { for (Listener l : listeners) l.onPrivateChat(m); }
    private void notifyVote(Message m) { for (Listener l : listeners) l.onVote(m); }
    private void notifyKill(Message m) { for (Listener l : listeners) l.onKill(m); }
    private void notifyHeal(Message m) { for (Listener l : listeners) l.onHeal(m); }
    private void notifyInvestigate(Message m) { for (Listener l : listeners) l.onInvestigate(m); }
    private void notifyStartGame(Message m) { for (Listener l : listeners) l.onStartGame(m); }
    private void notifyEndGame(Message m) { for (Listener l : listeners) l.onEndGame(m); }
    private void notifySystem(Message m) { for (Listener l : listeners) l.onSystem(m); }
    private void notifyError(Message m) { for (Listener l : listeners) l.onError(m); }
    private void notifyUnknown(Message m) { for (Listener l : listeners) l.onUnknown(m); }
    private void notifyMalformed(String json, Exception e) { for (Listener l : listeners) l.onMalformed(json, e); }
    private void notifyValidationFailed(Message m, String reason) { for (Listener l : listeners) l.onValidationFailed(m, reason); }

    /* ==================== Builders & Utils ==================== */

    /** Tạo Message nhanh với timestamp hiện tại. */
    public static Message of(MessageType type, String sender, String content) {
        Message m = new Message();
        m.setType(Objects.requireNonNull(type, "type"));
        m.setSender(sender);
        m.setContent(content);
        m.setTimestamp(System.currentTimeMillis());
        return m;
    }

    /** Helper tạo JSON nhanh. Ném RuntimeException nếu serialize lỗi. */
    public static String json(MessageType type, String sender, String content) {
        try {
            return JsonUtil.toJson(of(type, sender, content));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ===== Fallback: nếu "timestamp" là chuỗi ISO thì chuyển -> millis để parse lại
    private static final Pattern TS_STRING = Pattern.compile("\"timestamp\"\\s*:\\s*\"([^\"]+)\"");

    private static String coerceTimestampToMillis(String json) {
        Matcher m = TS_STRING.matcher(json);
        if (!m.find()) return json;
        String iso = m.group(1);
        long millis = parseIsoToMillis(iso);
        if (millis <= 0) return json; // không đổi nếu parse thất bại
        // Thay thế lần đầu (đủ vì timestamp thường xuất hiện 1 lần)
        return m.replaceFirst("\"timestamp\":" + millis);
    }

    private static long parseIsoToMillis(String s) {
        try { return Instant.parse(s).toEpochMilli(); } catch (Exception ignore) {}
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception ignore) {}
        try { return ZonedDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception ignore) {}
        return 0L;
    }
}
