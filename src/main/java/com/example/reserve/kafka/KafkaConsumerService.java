package com.example.reserve.kafka;

import com.example.reserve.sse.SseEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class KafkaConsumerService {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "queue_system_db.queue_system_db.outbox")
    public void kafkaConsume(String message) {
        try {

            JsonNode node = objectMapper.readTree(message);
            JsonNode payload = node.path("payload");

            String queueType = payload.get("queue_type").asText();
            String userId = payload.get("user_id").asText();

            log.info("consume - queueType : {} , userId : {}", queueType, userId);

            SseEventService.sink.tryEmitNext(queueType);

        } catch (Exception e) {
            log.error("Kafka 메시지 소비 실패", e);
        }
    }
}
