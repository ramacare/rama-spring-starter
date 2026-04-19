package org.rama.demo.listener.book;

import lombok.extern.slf4j.Slf4j;
import org.rama.demo.event.book.BookCreated;
import org.rama.demo.event.book.BookUpdated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BookEventListener {

    @EventListener
    public void onBookCreated(BookCreated event) {
        log.info("Book created: {} — {}", event.getEntity().getId(), event.getEntity().getTitle());
    }

    @EventListener
    public void onBookUpdated(BookUpdated event) {
        log.info("Book updated: {} — {}", event.getEntity().getId(), event.getEntity().getTitle());
    }
}
