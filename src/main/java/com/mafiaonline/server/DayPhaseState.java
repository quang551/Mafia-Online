package com.mafiaonline.server;

import java.util.concurrent.atomic.AtomicReference;

public class DayPhaseState {

    public enum DaySubPhase { CHAT, VOTE, RESOLVE }

    // có thể override bằng VM options:
    // -Dday.chat.seconds=60 -Dday.vote.seconds=90
    private final int chatSeconds = Integer.getInteger("day.chat.seconds", 60);
    private final int voteSeconds = Integer.getInteger("day.vote.seconds", 90);

    // trạng thái ngày
    private volatile int dayNumber = 0;
    private final AtomicReference<DaySubPhase> daySubPhase =
            new AtomicReference<>(DaySubPhase.CHAT);
    private volatile long subPhaseEndEpochMs = 0L;

    public int getChatSeconds() { return chatSeconds; }
    public int getVoteSeconds() { return voteSeconds; }

    public DaySubPhase getDaySubPhase() { return daySubPhase.get(); }
    public void setDaySubPhase(DaySubPhase p) { daySubPhase.set(p); }

    public long getSubPhaseEndEpochMs() { return subPhaseEndEpochMs; }
    public void setSubPhaseEndEpochMs(long ms) { subPhaseEndEpochMs = ms; }

    public boolean isVotingOpen() {
        return getDaySubPhase() == DaySubPhase.VOTE;
    }

    public int getDayNumber() { return dayNumber; }
    public void nextDay() { dayNumber++; }
}
