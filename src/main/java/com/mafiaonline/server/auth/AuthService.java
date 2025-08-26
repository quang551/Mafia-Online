package com.mafiaonline.server.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * AuthService (NO HASH) — lưu username/mật khẩu dạng PLAIN TEXT vào ./data/accounts.json
 * Cấu hình đường dẫn bằng VM options (tuỳ chọn):
 *   -Dauth.data.dir=/var/mafia   -Dauth.accounts.file=users.json
 *
 * API:
 *   String register(String username, String password)  // null nếu OK, ngược lại trả về thông báo lỗi
 *   boolean login(String username, String password)
 *   boolean changePassword(String username, String oldPass, String newPass)  // tuỳ chọn
 *   boolean deleteUser(String username)  // tuỳ chọn
 */
public class AuthService {
    // Policy tối thiểu (đặt ở đây để không cần sửa Protocol.java)
    public static final String USERNAME_REGEX = "^[A-Za-z0-9_]{3,16}$";
    public static final int MIN_PASSWORD_LEN = 6;
    public static final int MAX_PASSWORD_LEN = 64;

    private static final Pattern USERNAME_PATTERN = Pattern.compile(USERNAME_REGEX);
    private final ObjectMapper mapper = new ObjectMapper();

    private final Path dataDir;
    private final Path accFile;

    // Lưu PLAIN TEXT: username -> password
    private final Map<String, String> accounts = new ConcurrentHashMap<>();

    public AuthService() {
        // Có thể đổi nơi lưu bằng system properties
        this.dataDir = Paths.get(System.getProperty("auth.data.dir", "data"));
        this.accFile = dataDir.resolve(System.getProperty("auth.accounts.file", "accounts.json"));
        load();
    }

    /** Đọc dữ liệu từ file vào bộ nhớ (nếu chưa có file thì coi như rỗng). */
    public synchronized void load() {
        try {
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
            if (Files.exists(accFile)) {
                Map<String, String> m = mapper.readValue(
                        accFile.toFile(),
                        new TypeReference<Map<String, String>>() {}
                );
                accounts.clear();
                if (m != null) accounts.putAll(m);
            }
        } catch (IOException e) {
            System.err.println("AuthService load error: " + e.getMessage());
        }
    }

    /** Ghi file kiểu atomic (viết .tmp rồi đổi tên) để tránh hỏng file khi đang ghi. */
    private synchronized void saveAtomic() {
        try {
            Path tmp = accFile.resolveSibling(accFile.getFileName().toString() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), accounts);
            try {
                Files.move(tmp, accFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, accFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("AuthService save error: " + e.getMessage());
        }
    }

    /**
     * Đăng ký tài khoản mới. Trả về null nếu OK; ngược lại trả message lỗi cho client hiển thị.
     */
    public synchronized String register(String username, String password) {
        String err = validate(username, password);
        if (err != null) return err;
        if (accounts.containsKey(username)) return "Username đã tồn tại.";
        accounts.put(username, password);  // LƯU PLAIN TEXT
        saveAtomic();
        return null;
    }

    /** Đăng nhập: so sánh mật khẩu plain text. */
    public boolean login(String username, String password) {
        String stored = accounts.get(username);
        return stored != null && stored.equals(password);
    }

    /** (Tuỳ chọn) Đổi mật khẩu: yêu cầu đúng mật khẩu cũ. */
    public synchronized boolean changePassword(String username, String oldPass, String newPass) {
        String stored = accounts.get(username);
        if (stored == null || !stored.equals(oldPass)) return false;
        if (newPass == null || newPass.length() < MIN_PASSWORD_LEN || newPass.length() > MAX_PASSWORD_LEN) return false;
        accounts.put(username, newPass);
        saveAtomic();
        return true;
    }

    /** (Tuỳ chọn) Xoá tài khoản. */
    public synchronized boolean deleteUser(String username) {
        if (accounts.remove(username) != null) {
            saveAtomic();
            return true;
        }
        return false;
    }

    /** Validate tối thiểu cho username/password. */
    private String validate(String username, String password) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches())
            return "Username chỉ gồm A-Z, a-z, 0-9, _ và dài 3-16 ký tự.";
        if (password == null || password.length() < MIN_PASSWORD_LEN || password.length() > MAX_PASSWORD_LEN)
            return "Mật khẩu dài 6-64 ký tự.";
        return null;
    }
}
