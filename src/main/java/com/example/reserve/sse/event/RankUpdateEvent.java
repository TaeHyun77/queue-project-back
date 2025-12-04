package com.example.reserve.sse.event;

import lombok.Getter;

@Getter
public final class RankUpdateEvent {

    private String event = "update";

    private Long rank;

    public RankUpdateEvent(Long rank) {
        this.rank = rank;
    }
}
