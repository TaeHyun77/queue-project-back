package com.example.reserve.entity.outbox.user;

import com.example.reserve.entity.outbox.QueueStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@Builder
@Getter
@NoArgsConstructor
@Entity
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;

    @Enumerated(EnumType.STRING)
    private QueueStatus queue_Queue_status;

    public void updateStatus(QueueStatus queue_Queue_status) {
        this.queue_Queue_status = queue_Queue_status;
    }
}
