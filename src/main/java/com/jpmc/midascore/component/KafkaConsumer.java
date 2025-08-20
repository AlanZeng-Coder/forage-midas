package com.jpmc.midascore.component;

import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Incentive;
import com.jpmc.midascore.foundation.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class KafkaConsumer {
    private static final Logger logger = LoggerFactory.getLogger(KafkaConsumer.class);
    private final DatabaseConduit databaseConduit;
    private final RestTemplate restTemplate;

    public KafkaConsumer(DatabaseConduit databaseConduit, RestTemplate restTemplate) {
        this.databaseConduit = databaseConduit;
        this.restTemplate = restTemplate;
    }

    @KafkaListener(topics = "${general.kafka-topic}", groupId = "midas-consumer-group")
    public void consume(Transaction transaction) {
        UserRecord sender = databaseConduit.findUserById(transaction.getSenderId());
        UserRecord recipient = databaseConduit.findUserById(transaction.getRecipientId());
        if (sender != null && recipient != null && sender.getBalance() >= transaction.getAmount()) {
            // 调用激励 API
            Incentive incentive = restTemplate.postForObject("http://localhost:8080/incentive", transaction, Incentive.class);
            float incentiveAmount = (incentive != null) ? incentive.getAmount() : 0.0f;

            // 更新余额
            sender.setBalance(sender.getBalance() - transaction.getAmount());
            recipient.setBalance(recipient.getBalance() + transaction.getAmount() + incentiveAmount);

            // 保存交易记录
            TransactionRecord transactionRecord = new TransactionRecord(sender, recipient, transaction.getAmount(), incentiveAmount);
            databaseConduit.saveTransaction(transactionRecord);
            databaseConduit.save(sender);
            databaseConduit.save(recipient);

            logger.info("Processed Transaction: senderId={}, recipientId={}, amount={}, incentive={}",
                    transaction.getSenderId(), transaction.getRecipientId(), transaction.getAmount(), incentiveAmount);
        } else {
            logger.info("Invalid Transaction: senderId={}, recipientId={}, amount={}",
                    transaction.getSenderId(), transaction.getRecipientId(), transaction.getAmount());
        }
    }
}