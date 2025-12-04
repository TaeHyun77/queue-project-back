package com.example.reserve.sse.event;

import lombok.Getter;

@Getter
public final class ErrorEvent {

    private String error = "error";

    private String message;

    public ErrorEvent(String message) {
        this.message = message;
    }
}
