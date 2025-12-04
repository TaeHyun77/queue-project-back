package com.example.reserve.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Slf4j
@RequiredArgsConstructor
@RequestMapping("/queue")
@RestController
public class QueueController {

    private final QueueService queueService;

    @PostMapping("/register/{queueType}/{userId}")
    public Mono<Void> registerUser(@PathVariable("queueType") String queueType,
                                   @PathVariable("userId") String userId) {

        Instant now = Instant.now();
        long enterTimestamp = now.getEpochSecond() * 1_000_000_000L + now.getNano() / 1000L;

        log.info("userId : {}, enterTimestamp : {}", userId, enterTimestamp);

        return queueService.registerUserToWaitQueue(userId, queueType, enterTimestamp);
    }

    // 대기열 or 참가열에서 사용자 존재 유무 확인
    @GetMapping("/isExist")
    public Mono<Boolean> isExistUserInQueue(@RequestParam(name = "user_id") String userId,
                                            @RequestParam(name = "queueType", defaultValue = "reserve") String queueType,
                                            @RequestParam(name = "queueCategory") String queueCategory) {
        return queueService.isExistUserInWaitOrAllow(userId, queueType, queueCategory);
    }

    // 대기열 or 참가열에서 사용자 순위 조회
    @GetMapping("/search/ranking")
    public Mono<Long> searchUserRanking(@RequestParam(name = "user_id") String userId,
                                        @RequestParam(name = "queueType", defaultValue = "reserve") String queueType,
                                        @RequestParam(name = "queueCategory") String queueCategory) {
        return queueService.searchUserRanking(userId, queueType, queueCategory);
    }

    // 대기열 or 참가열에서 사용자 제거
    @DeleteMapping("/cancel")
    public Mono<Void> cancelUser(@RequestParam(name = "user_id") String userId,
                                 @RequestParam(name = "queueType", defaultValue = "reserve") String queueType,
                                 @RequestParam(name = "queueCategory") String queueCategory) {
        return queueService.cancelWaitUser(userId, queueType, queueCategory);
    }

    // 대기열 상위 count명을 참가열 이동
    @PostMapping("/allow")
    public Mono<?> allowUser(@RequestParam(name = "queueType", defaultValue = "reserve") String queueType,
                             @RequestParam(name = "count") Long count) {
        return queueService.allowUser(queueType, count);
    }

    // 토큰 유효성 확인
    @GetMapping("/isValidateToken")
    public Mono<Boolean> isAccessTokenValid(@RequestParam(name = "user_id") String userId,
                                            @RequestParam(name = "queueType", defaultValue = "reserve") String queueType,
                                            @RequestParam(name = "token") String token) {
        return queueService.isAccessTokenValid(userId, queueType, token);
    }

    // 쿠키 토큰 저장
    @GetMapping("/createCookie")
    public Mono<ResponseEntity<String>> sendCookie(@RequestParam(name = "user_id") String userId,
                                                   @RequestParam(defaultValue = "reserve") String queueType,
                                                   ServerHttpResponse response) {
        return queueService.sendCookie(userId, queueType, response);
    }
}

