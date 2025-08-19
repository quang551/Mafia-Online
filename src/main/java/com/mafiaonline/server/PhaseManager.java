package com.mafiaonline.server;

public class PhaseManager {
    private GameRoom gameRoom;

    public PhaseManager(GameRoom gameRoom) {
        this.gameRoom = gameRoom;
    }

    public void startDay() {
        gameRoom.setState(GameState.DAY);
        System.out.println("Pha ban ngày bắt đầu. Người chơi thảo luận và vote.");
    }

    public void startNight() {
        gameRoom.setState(GameState.NIGHT);
        System.out.println("Pha ban đêm bắt đầu. Mafia chọn giết, Doctor chọn cứu.");
    }

    public void endGame() {
        gameRoom.setState(GameState.END);
        System.out.println("Game đã kết thúc!");
    }
}
