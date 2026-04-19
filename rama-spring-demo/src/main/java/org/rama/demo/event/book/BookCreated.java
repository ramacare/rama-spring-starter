package org.rama.demo.event.book;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.rama.demo.entity.book.Book;
import org.rama.event.IEntityEvent;

@Getter
@AllArgsConstructor
public class BookCreated implements IEntityEvent<Book> {
    private Book entity;
}
