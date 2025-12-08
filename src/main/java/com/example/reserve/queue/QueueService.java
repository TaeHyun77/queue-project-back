package com.example.reserve.queue;

import com.example.reserve.entity.outbox.QueueStatus;
import com.example.reserve.entity.outbox.user.User;
import com.example.reserve.entity.outbox.user.UserRepository;
import com.example.reserve.exception.ErrorCode;
import com.example.reserve.exception.ReserveException;
import com.example.reserve.entity.outbox.outbox.Outbox;
import com.example.reserve.entity.outbox.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.example.reserve.QueueConstant.*;

@Slf4j
@Getter
@RequiredArgsConstructor
@Service
public class QueueService {

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final ObjectMapper objectMapper;

    private final UserRepository userRepository;
    private final OutboxRepository outboxRepository;

    private final Long maxAllowedUsers = 3L;
    private final List<String> queueTypes = List.of("reserve");

    /**
     * 대기열 등록 - WAIT
     * */
    public Mono<Void> registerUserToWaitQueue(
            String userId,
            String queueType,
            long enterTimestamp
    ) {

        String queueKey = queueType + WAIT_QUEUE;

        return Mono.zip(
            isExistUserInWaitOrAllow(userId, queueType, "wait"),
            isExistUserInWaitOrAllow(userId, queueType, "allow")
        )
        .flatMap(tuple -> { // flatMap : 콜백 안에서 Mono/Flux 를 리턴해야하는 경우 사용
            boolean inWait = tuple.getT1();
            boolean inAllow = tuple.getT2();

            if (inWait || inAllow) {
                return Mono.error(new ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.ALREADY_REGISTERED_USER));
            }

            return reactiveRedisTemplate.opsForZSet()
                    .add(queueKey, userId, enterTimestamp)
                    .flatMap(added -> {
                        if (!added) {
                            return Mono.error(new ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.ALREADY_REGISTERED_USER));
                        }

                        return updateQueue(queueType, userId, QueueStatus.QUEUE_REGISTERED);
                    })
                    .doOnNext(m -> log.info("{}님 사용자 대기열 등록 성공", userId));
        })
        .doOnError(e -> log.error("대기열 등록 오류: userId={}, {}", userId, e.getMessage()));

    }

    /**
     * 대기열 or 참가열에서 사용자 존재 여부 확인
     */
    public Mono<Boolean> isExistUserInWaitOrAllow(
            String userId,
            String queueType,
            String queueCategory
    ) {

        String keyType = toKeySuffix(queueCategory);
        String queueName = queueType + keyType;

        return reactiveRedisTemplate.opsForZSet()
                .rank(queueName, userId)
                .map(rank -> true) // rank 값이 있다면 true로 변환, 해당 사용자가 존재하지 않는다면 Mono.empty() 반환
                .defaultIfEmpty(false)
                .doOnNext(exists ->
                        log.info("{}님 {} 존재 여부 : {}", userId, queueCategory.equals("wait") ? "대기열" : "참가열", exists)
                )
                .doOnError(e -> log.error("사용자 존재 여부 파악 오류: userId={}, {}", userId, e.getMessage()));
    }

    /**
     * 대기열 or 참가열에서 사용자 순위 조회
     * */
    public Mono<Long> searchUserRanking(
            String userId,
            String queueType,
            String queueCategory
    ) {

        String keyType = toKeySuffix(queueCategory);
        String queueName = queueType + keyType;

        return reactiveRedisTemplate.opsForZSet()
                .rank(queueName, userId)
                .map(rank -> rank + 1) // 사용자 순위는 0부터 시작하므로 +1
                .defaultIfEmpty(-1L) // 해당 사용자가 없으면 -1을 반환
                .doOnNext(rank -> {
                    if (rank > 0) {
                        log.info("[{}] {}님의 현재 순위 : {}번", queueCategory, userId, rank);
                    } else {
                        log.warn("[{}] {}님이 존재하지 않습니다", queueCategory, userId);
                    }
                })
                .doOnError(e -> log.error("사용자 순위 조회 오류: userId={}, {}", userId, e.getMessage()));
    }

    /**
     * 대기열 or 참가열에서 등록된 사용자 제거 - CANCELED
     * */
    public Mono<Void> cancelWaitUser(
            String userId,
            String queueType,
            String queueCategory
    ) {

        String queueName = queueCategory.equals("wait") ? queueType + WAIT_QUEUE : queueType + ALLOW_QUEUE;

        return reactiveRedisTemplate.opsForZSet()
                .remove(queueName, userId)
                .flatMap(rmv -> {
                    if (rmv == 0) {
                        return Mono.error(new ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.USER_NOT_FOUND_IN_THE_QUEUE));
                    }

                    // 참가열 삭제면, TTL도 삭제하도록
                    if (queueCategory.equals("allow")) {
                        return reactiveRedisTemplate.opsForZSet()
                                .remove(TOKEN_TTL_INFO, userId)
                                .doOnSuccess(ttlRmv -> {
                                    if (ttlRmv > 0) {
                                        log.info("{}님의 TTL 키 삭제 완료", userId);
                                    } else {
                                        log.warn("{}님의 TTL 키 미존재로 삭제되지 않음", userId);
                                    }
                                })
                                .then(updateQueue(queueType, userId, QueueStatus.QUEUE_REMOVED));
                    }

                    return updateQueue(queueType, userId, QueueStatus.QUEUE_REMOVED);

                })
                .doOnSuccess(v ->
                        log.info("[{}] {}님 대기열/참가열 취소 완료", queueCategory, userId)
                )
                .doOnError(e ->
                        log.error("[{}] {}님 취소 중 오류: {}", queueCategory, userId, e.getMessage())
                )
                .then(); // 최종 반환을 Mono<Void>로 변환

    }

    /**
     * 유효성 검사를 위한 토큰 생성
     * */
    public static Mono<String> generateAccessToken(
            String userId,
            String queueType
    ) {
        return Mono.fromCallable(() -> {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = queueType + ACCESS_TOKEN + userId;
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));

            return hex.toString();
        });
    }


    /**
     * 생성한 토큰을 쿠키에 저장
     * */
    public Mono<ResponseEntity<String>> sendCookie(
            String userId,
            String queueType,
            ServerHttpResponse response
    ) {

        String encodedName = URLEncoder.encode(userId, StandardCharsets.UTF_8);

        return QueueService.generateAccessToken(userId, queueType)
                .map(token -> {
                    ResponseCookie cookie = ResponseCookie.from(queueType + "_user-access-cookie_" + encodedName, token)
                            .path("/")
                            .maxAge(Duration.ofSeconds(300))
                            .build();

                    response.addCookie(cookie);

                    return ResponseEntity.ok("쿠키 발급 완료");
                });
    }

    /**
     * 토큰 유효성 검사
     * */
    public Mono<Boolean> isAccessTokenValid(String userId, String queueType, String token) {

        return reactiveRedisTemplate.opsForZSet()
                .score(TOKEN_TTL_INFO, userId)
                .flatMap(expireTime -> {
                    long now = Instant.now().getEpochSecond();

                    if (expireTime.longValue() < now) {
                        return Mono.just(false);
                    }

                    return generateAccessToken(userId, queueType)
                            .map(generated -> generated.equals(token));
                })
                .defaultIfEmpty(false); // score()가 null 이면 TTL 정보가 없으므로 false
    }


    /**
     * 대기열에 있는 상위 count 명을 참가열로 옮기고, 토큰을 생성하여 redis에 저장 ( 유효 기간 10분 ) - ALLOW
     */
    public Mono<Long> allowUser(String queueType, Long count) {

        Instant now = Instant.now();
        long timestamp = now.getEpochSecond() * 1_000_000_000L + now.getNano();
        long expireEpochSeconds = now.plus(Duration.ofMinutes(10)).getEpochSecond();

        String waitQueueKey = queueType + WAIT_QUEUE;
        String allowQueueKey = queueType + ALLOW_QUEUE;

        return reactiveRedisTemplate.opsForZSet()
                .popMin(waitQueueKey, count)
                .flatMap(member -> {
                    String userId = member.getValue();

                    // allow queue에 추가 → TTL 저장 → updateQueue
                    Mono<Boolean> addAllow = reactiveRedisTemplate.opsForZSet()
                            .add(allowQueueKey, userId, timestamp);

                    Mono<Boolean> addTTL = reactiveRedisTemplate.opsForZSet()
                            .add(TOKEN_TTL_INFO, userId, expireEpochSeconds);

                    return addAllow
                            .then(addTTL)
                            .then(updateQueue(queueType, userId, QueueStatus.QUEUE_ALLOW))
                            .thenReturn(userId);
                })
                .count()
                .doOnSuccess(allowedCount ->
                        log.info("참가열로 이동된 사용자 수: {}", allowedCount)
                );
    }


    /**
     * 대기열의 사용자를 참가열로 maxAllowedUsers 명 옮기는 scheduling 코드
     * */
    @Scheduled(fixedDelay = 5000, initialDelay = 30000)
    public void moveUserToAllowQ() {

        queueTypes.forEach(queueType ->
                allowUser(queueType, maxAllowedUsers)
                        .doOnError(e -> log.error("[{}] allowUser 오류", queueType, e))
                        .subscribe()
        );
    }


    // Outbox 테이블에 변동 사항이 발생하면 kafka 이벤트가 ( 전체 레코드가 JSON 형태로 ) 발행됨
    @Transactional
    public Mono<Void> updateQueue(String queueType, String userId, QueueStatus queueStatus) {

        return Mono.fromCallable(() -> {
                    // 유저 조회
                    User user = userRepository.findByUserId(userId)
                            .map(existing -> {
                                log.info("상태 변경 ⇒ {}", queueStatus);

                                existing.updateStatus(queueStatus);
                                return existing;
                            })
                            .orElseGet(() -> {
                                // 신규 유저 생성
                                return User.builder()
                                        .userId(userId)
                                        .queue_Queue_status(queueStatus)
                                        .build();
                            });

                    // 유저 저장
                    User savedUser = userRepository.save(user);

                    // Outbox 저장
                    Outbox outbox = Outbox.builder()
                            .aggregate_type("user")
                            .aggregate_id(savedUser.getId())
                            .event_type(queueStatus)
                            .queueType(queueType)
                            .userId(userId)
                            .build();

                    outboxRepository.save(outbox);

                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())  // 블로킹 전용 스레드풀에서 실행
                .then();
    }

    private String toKeySuffix(String queueCategory) {
        return "wait".equals(queueCategory) ? WAIT_QUEUE : ALLOW_QUEUE;
    }
}
