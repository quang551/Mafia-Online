package com.mafiaonline.server;

public enum Role {
    MAFIA("Mafia - tiêu diệt dân"),
    VILLAGER("Dân thường - không kỹ năng"),
    DOCTOR("Doctor - cứu 1 người mỗi đêm"),
    DETECTIVE("Detective - điều tra 1 người mỗi đêm"),
    JESTER("Jester - thắng nếu bị treo cổ"),
    BODYGUARD("Bodyguard - bảo vệ 1 người (có thể hy sinh)");

    private final String description;
    Role(String desc) { this.description = desc; }
    public String getDescription() { return description; }
    @Override public String toString() { return name(); }
}
