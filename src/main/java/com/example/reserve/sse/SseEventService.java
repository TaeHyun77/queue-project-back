package com.example.reserve.sse;

import com.example.reserve.queue.QueueService;
import com.example.reserve.exception.ErrorCode;
import com.example.reserve.exception.ReserveException;
import com.example.reserve.sse.event.ErrorEvent;
import com.example.reserve.sse.event.RankUpdateEvent;
import com.example.reserve.sse.event.UserConfirmEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class SseEventService {

    private final ObjectMapper objectMapper;
    private final QueueService queueService;

    public static final Sinks.Many<String> sink = Sinks.many().replay().limit(1);

    public Flux<ServerSentEvent<String>> streamQueueEvents(String userId, String queueType) {
        return sink.asFlux()
                .filter(event -> event.equals(queueType)) // 특정 queueType 내의 사용자에게만 이벤트 전송
                .flatMap(event ->

                        // 참가열에 존재 여부에 따라 분기
                        queueService.isExistUserInWaitOrAllow(userId, queueType, "allow")
                                .flatMap(isAllowed -> isAllowed
                                        ? handleAllowedUser(userId)
                                        : handleWaitingUser(userId, queueType)
                                )
                );
    }

    // 참가열에 존재한다면 "confirm" 이벤트를 전송하여 타겟 페이지로 이동하게 함
    private Mono<ServerSentEvent<String>> handleAllowedUser(String userId) {
        try {
            UserConfirmEvent event = new UserConfirmEvent(userId);
            String json = objectMapper.writeValueAsString(event);

            return Mono.just(ServerSentEvent.builder(json).build());
        } catch (JsonProcessingException ex) {
            return Mono.error(
                    new ReserveException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.ALLOW_STATUS_JSON_EXCEPTION)
            );
        }
    }

    // 대기열에 존재한다면
    private Mono<ServerSentEvent<String>> handleWaitingUser(String userId, String queueType) {
        return queueService.searchUserRanking(userId, queueType, "wait")
                .flatMap(rank -> {
                    try {
                        // 존재하지 않는 사용자
                        if (rank <= 0) {
                            ErrorEvent errorEvent = new ErrorEvent("대기열에 존재하지 않습니다.");
                            String json = objectMapper.writeValueAsString(errorEvent);

                            return Mono.just(ServerSentEvent.builder(json).build());
                        }

                        // 정상 랭킹 반환
                        RankUpdateEvent event = new RankUpdateEvent(rank);
                        String json = objectMapper.writeValueAsString(event);

                        return Mono.just(ServerSentEvent.builder(json).build());

                    } catch (JsonProcessingException ex) {
                        return Mono.error(
                                new ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.JSON_PARSING_ERROR)
                        );
                    }
                })
                .onErrorResume(ex -> {
                    String json = "{\"event\":\"error\",\"message\":\"순위 조회 중 오류 발생\"}";

                    return Mono.just(ServerSentEvent.builder(json).build());
                });
    }

}
