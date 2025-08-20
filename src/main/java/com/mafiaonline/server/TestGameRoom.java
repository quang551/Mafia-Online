package com.mafiaonline.server;

/**
 * TestGameRoom (Day3):
 * - tạo 5 player
 * - startGame() -> gán role
 * - in danh sách role
 * - mô phỏng kill để kích hoạt checkWinCondition
 */
public class TestGameRoom {
    public static void main(String[] args) throws InterruptedException {
        GameRoom room = new GameRoom();

        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.addPlayer("Charlie");
        room.addPlayer("David");
        room.addPlayer("Eve");

        System.out.println("=== Trước khi start ===");
        room.printPlayers();
        System.out.println("Alive count: " + room.getAliveCount());

        System.out.println("\n--- Start game: assign roles ---");
        room.startGame();

        System.out.println("\n--- After role assignment ---");
        room.printPlayers();

        // tìm 1 mafia để simulate kill
        String mafia = null;
        for (Player p : room.getPlayersAll()) {
            if (p.getRole() == Role.MAFIA) { mafia = p.getName(); break; }
        }
        System.out.println("\nFound mafia: " + mafia);

        // simulate: mafia kills two villagers one by one
        int kills = 0;
        for (Player p : room.getPlayersAll()) {
            if (kills >= 2) break;
            if (!p.getName().equals(mafia) && p.getRole() == Role.VILLAGER && p.isAlive()) {
                System.out.println("Simulate: " + p.getName() + " bị giết.");
                room.killPlayer(p.getName());
                kills++;
                Thread.sleep(300);
                room.checkWinCondition();
            }
        }

        System.out.println("\n--- Kết thúc test ---");
        room.printPlayers();
    }
}
