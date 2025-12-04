package com.example.reserve.sse.event;

import lombok.Getter;

@Getter
public final class UserConfirmEvent {

    private String event = "confirmed";

    private String userId;

    public UserConfirmEvent(String userId) {
        this.userId = userId;
    }
}
