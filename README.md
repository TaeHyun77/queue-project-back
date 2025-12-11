### 아키텍처
---

<p align="center"><img width="625" height="188" alt="Image" src="https://github.com/user-attachments/assets/0205be3b-61b1-4284-a1da-17e9aecd89a4" /><br><br>

**구현 과정**<br>
https://velog.io/@ayeah77/series/%EB%8C%80%EA%B8%B0%EC%97%B4-%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8<br><br>

### 사용 기술
---
Backend : SpringBoot, Spring Webflux, Java

Frontend : React.js, JavaScript

DB/Cache : MySQL, Redis

INFRA : Kafka, Kafka Connect

ETC : SSE, MySQL Debezium Connector <br><br>

### 요청 흐름

---

1. 클라이언트가 대기열 등록을 요청하면 해당 클라이언트와 SSE 연결을 맺고, 어떤 대기열인지 나타내는 `queueType`과 사용자 ID인 `userId`를 서버로 전달하며 서버는 요청이 들어온 시각을 `timestamp`로 기록합니다.
    
    이때 서버는 SSE 스트림을 반환하며, 이후 스트림에 이벤트가 push될 때마다 클라이언트는 이를 실시간으로 수신받을 수 있게 됩니다.
   
    
2. 대기열 등록 요청이 처리되면, 서버는 Redis의 해당 queueType ZSET에 `userId`를 key로, 요청 시각을 score로 저장하여 자동으로 정렬된 대기 순서를 유지합니다.
   
3. 이후 사용자의 대기열 상태 정보를 DB에 저장하고, 이 변경 사항을 Debezium 커넥터가 CDC를 통해 감지합니다. 감지된 업데이트는 Outbox 패턴을 통해 변경된 queueType 대기열의 queueType 값을 Kafka로 Produce 합니다.
   
4. Kafka 메시지를 Consumer가 처리하면, 연결된 SSE 스트림을 통해 해당 queueType에 존재하는 사용자들의 실시간 상태가 전달됩니다. 사용자가 아직 대기열에 있다면 현재 순위를, 허용열에 진입했다면 `confirm` 이벤트를 전송하여 클라이언트가 해당 페이지로 이동할 수 있도록 합니다.
   
5. 이러한 흐름을 통해 대기열 등록/삭제/이동 등 상태 변화가 발생할 때마다, 해당 대기열에 연결된 모든 사용자는 자신의 대기열 상태를 실시간 SSE 이벤트로 전달받게 됩니다.<br><br>

### 주요 기술 
---
**Transactional Outbox Pattern과 MySQL Debezium Connector을 사용하는 구조**

<img width="739" height="332" alt="Image" src="https://github.com/user-attachments/assets/e59b0f03-9021-4374-8fc0-4436d120a306" />

[ Transactional Outbox + Debezium 구조 ]

Transactional Outbox 패턴과 Debezium을 함께 사용하면, DB 변경과 이벤트 생성은 하나의 트랜잭션으로 원자성을 보장하고( Outbox ), Outbox 테이블의 이벤트를 메시지 브로커로 전달( Debezium )할 수 있기 때문에 DB 상태와 이벤트 전달 간의 정합성을 맞출 수 있습니다.

Outbox 테이블 변경을 binlog에서 감지하여 Kafka로 publish하고, publish 실패 시 Debezium이 자동으로 재시도하므로 이벤트 유실이 없습니다.

이를 통해 DB 상태와 Kafka 이벤트 스트림 사이의 정합성을 유지할 수 있습니다.<br><br>

### 구조 개선
---
기존에는 Redis에 대기열 정보를 저장한 뒤 서버에서 직접 SSE 이벤트를 전송했지만, 장애가 발생하여 이벤트가 전달되지 않거나 소실될 경우 서비스에 큰 영향을 줄 수 있었습니다.

이러한 문제를 해결하기 위해 메세지 영속성을 보장하고 재처리가 가능한 Kafka 기반의 이벤트 전송 구조로 개선하였습니다.

또한, 특정 이벤트 발행에 실패할 경우, 데이터베이스에는 사용자의 상태 정보가 갱신되었음에도 이벤트는 전달되지 않아 DB와 이벤트 간의 정합성이 깨질 위험이 있었습니다. 

이를 해결하기 위해 Debezium MySQL Kafka 커넥터를 활용하여 데이터베이스 테이블의 변경 사항을 감지하고 이를 Kafka에 이벤트를 발행하도록 하여 데이터베이스 트랜잭션과 이벤트 간의 정합성 문제를 해결하였습니다.

결과적으로, Debezium 기반의 Transactional Outbox Pattern을 구현함으로써 DB와 이벤트 스트림 간의 불일치 문제를 해결하고, 이벤트 소실 방지, 순서 보장, 데이터 정합성을 해결할 수 있었습니다.<br><br>

### 추후 개선점
---
1. 비동기 로직에서 동기적인 DB 사용은 심각한 병목이 될 수 있으며, 대기열 시스템의 특성 상 사용자의 상태 정보를 저장할 필요가 없으므로 DB를 제거하고자 합니다.

   또한, 멱등성 정보를 저장할 때는 R2DBC를 활용하여 전체 비동기 구조와 일관되게 처리할 수 있도록 하고자 합니다.

2. Kotlin 기반으로 변경하여 코루틴을 사용함으로써 WebFlux의 Reactor 체인 구조를 동기적인 형식의 코드로 개선하여 가독성과 유지 보수성을 높히고자 합니다.

3. 단일 서버에서 분산 서버로 확장 함으로써 부하 분산 및 가용성을 확보할 계획입니다.
