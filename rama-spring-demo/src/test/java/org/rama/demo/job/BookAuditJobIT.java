package org.rama.demo.job;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
class BookAuditJobIT {

    @Autowired BookAuditJob job;
    @Autowired BookRepository bookRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void execute_withBooks_shouldProcessAll() throws Exception {
        transactionTemplate.execute(s -> {
            bookRepository.saveAndFlush(new Book("A"));
            bookRepository.saveAndFlush(new Book("B"));
            return null;
        });

        job.executeInternal(mock(org.quartz.JobExecutionContext.class));

        assertThat(job.getLastProcessedCount()).isGreaterThanOrEqualTo(2);
    }
}
