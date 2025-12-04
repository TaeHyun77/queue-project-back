package com.example.reserve.sse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Slf4j
@RequiredArgsConstructor
@RestController
public class SseEventController {

    private final SseEventService sseEventService;

    /*
     * 서버와 클라이언트가 SSE 스트림 연결
     * produces = MediaType.TEXT_EVENT_STREAM_VALUE : text/event-stream 타입의 응답을 반환할 것을 의미
     * ServerSentEvent<String> : SSE 형식의 메시지를 의미, Flux<>로 감쌌으므로 여러 개의 sse 메세지를 실시간으로 전송한다는 뜻
     * */
    @GetMapping(value = "/queue/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamQueue(@RequestParam String userId, @RequestParam String queueType) {

        return sseEventService.streamQueueEvents(userId, queueType);
    }
}