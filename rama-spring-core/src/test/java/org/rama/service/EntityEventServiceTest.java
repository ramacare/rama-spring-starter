package org.rama.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.event.IEntityEvent;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class EntityEventServiceTest {

    @Mock private ApplicationEventPublisher publisher;
    @Mock private TransactionRunnerService transactionRunnerService;

    @InjectMocks private EntityEventService service;

    @Test
    void publishEntityEvent_publishesImmediately_whenNotInTransaction() {
        Foo foo = new Foo("F-1");

        service.publishEntityEvent(FooEvent.class, foo, true);

        ArgumentCaptor<FooEvent> captor = ArgumentCaptor.forClass(FooEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getEntity()).isSameAs(foo);
    }

    @Test
    void publishEntityEvent_findsConstructorByAssignability_whenPayloadIsSubclass() {
        // Mimics the Hibernate bytecode-enhancement case: payload is a subclass of the
        // declared entity type, but the event constructor only knows the declared type.
        EnhancedFoo enhanced = new EnhancedFoo("F-2");

        service.publishEntityEvent(FooEvent.class, enhanced, false);

        ArgumentCaptor<FooEvent> captor = ArgumentCaptor.forClass(FooEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getEntity()).isSameAs(enhanced);
    }

    @Test
    void publishEntityEvent_dropsEvent_whenNoCompatibleConstructor() {
        // Bar has no constructor accepting a Foo — service logs and returns.
        service.publishEntityEvent(BarEvent.class, new Foo("F-3"), false);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    // ---- fixtures ----

    static class Foo {
        final String id;
        Foo(String id) { this.id = id; }
    }

    /** Stand-in for a Hibernate-enhanced subclass — the runtime class isn't Foo. */
    static class EnhancedFoo extends Foo {
        EnhancedFoo(String id) { super(id); }
    }

    public static class FooEvent implements IEntityEvent<Foo> {
        private final Foo entity;
        public FooEvent(Foo entity) { this.entity = entity; }

        @Override
        public Foo getEntity() { return entity; }
    }

    static class Bar {}

    public static class BarEvent implements IEntityEvent<Bar> {
        private final Bar entity;
        public BarEvent(Bar entity) { this.entity = entity; }

        @Override
        public Bar getEntity() { return entity; }
    }
}
