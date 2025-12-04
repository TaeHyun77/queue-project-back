package com.example.reserve.entity.outbox.outbox;

import com.example.reserve.entity.outbox.QueueStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Builder
@AllArgsConstructor
@Entity
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregate_type;

    private Long aggregate_id;

    @Enumerated(EnumType.STRING)
    private QueueStatus event_type;

    private String queueType;

    private String userId;
}
