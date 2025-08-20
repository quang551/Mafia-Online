package com.mafiaonline.server;

public enum Role {
    UNASSIGNED("Chưa gán"),
    MAFIA("Mafia: tiêu diệt dân làng"),
    DETECTIVE("Detective: điều tra role của 1 người"),
    DOCTOR("Doctor: cứu 1 người mỗi đêm"),
    BODYGUARD("Bodyguard: bảo vệ 1 người và hy sinh nếu bị tấn công"),
    JESTER("Jester: nếu bị treo cổ sẽ thắng ngay"),
    VILLAGER("Villager: dân thường");

    private final String desc;
    Role(String d) { this.desc = d; }
    public String getDescription() { return desc; }
    @Override public String toString() { return name(); }
}
