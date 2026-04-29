package org.rama.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.event.IEntityEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Constructor;

@RequiredArgsConstructor
@Slf4j
public class EntityEventService {
    private final ApplicationEventPublisher publisher;
    private final TransactionRunnerService transactionRunnerService;

    public <T extends IEntityEvent<?>> void publishEntityEvent(Class<T> eventClass, Object payload, Boolean afterCommit) {
        final T event;
        try {
            Constructor<T> ctor = findCompatibleConstructor(eventClass, payload);
            event = ctor.newInstance(payload);
        } catch (ReflectiveOperationException ex) {
            log.error("Event {} must have a public single-arg constructor accepting {}",
                    eventClass.getName(), payload.getClass().getName(), ex);
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

    /**
     * Locate a public single-arg constructor whose declared parameter type is assignable
     * from {@code payload}'s runtime type. Matching by assignability — rather than the
     * exact runtime class — keeps event publishing working when consumers enable
     * Hibernate bytecode enhancement, which causes {@code payload.getClass()} to return
     * an enhanced subclass that no event constructor declares.
     */
    @SuppressWarnings("unchecked")
    private static <T> Constructor<T> findCompatibleConstructor(Class<T> eventClass, Object payload) throws NoSuchMethodException {
        for (Constructor<?> ctor : eventClass.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 1 && params[0].isInstance(payload)) {
                return (Constructor<T>) ctor;
            }
        }
        throw new NoSuchMethodException(
                "No public single-arg constructor on " + eventClass.getName()
                        + " accepting " + payload.getClass().getName());
    }
}
