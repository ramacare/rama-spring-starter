package org.rama.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.event.IEntityEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RequiredArgsConstructor
@Slf4j
public class EntityEventService {
    private final ApplicationEventPublisher publisher;
    private final TransactionRunnerService transactionRunnerService;

    public <T extends IEntityEvent<?>> void publishEntityEvent(Class<T> eventClass, Object payload, Boolean afterCommit) {
        final T event;
        try {
            event = eventClass.getConstructor(payload.getClass()).newInstance(payload);
        } catch (ReflectiveOperationException ex) {
            log.error("Event {} must have a public constructor (Object payload)", eventClass.getName(), ex);
            return;
        }

        if (TransactionSynchronizationManager.isActualTransactionActive() && afterCommit) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    transactionRunnerService.runInNewTx(() -> publisher.publishEvent(event));
                }
            });
        } else {
            publisher.publishEvent(event);
        }
    }
}
