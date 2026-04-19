package org.rama.demo.job;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookAuditJob extends QuartzJobBean {

    private final BookRepository bookRepository;
    @Getter
    private volatile int lastProcessedCount;

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        List<Book> all = bookRepository.findAll();
        all.forEach(b -> log.info("Audited book: {} — {}", b.getId(), b.getTitle()));
        lastProcessedCount = all.size();
    }
}
