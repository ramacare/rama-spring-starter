package org.rama.demo.audit;

import org.quartz.JobDataMap;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.rama.job.SmartJob;

/**
 * Test probe: a {@link SmartJob} that persists an {@link org.rama.entity.Auditable}
 * entity. The save (and thus the global userstamp listener) runs on the Quartz worker
 * thread, which has no authenticated user — so {@code SmartJob.execute} must establish
 * a synthetic principal for the stamp to be attributable.
 *
 * Registered via {@code @Import}, not {@code @Component}, so it stays out of the demo
 * component scan.
 */
public class StampingProbeJob extends SmartJob {

    static final String TITLE = "Quartz Stamped Book";

    private final BookRepository bookRepository;

    public StampingProbeJob(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    @Override
    public void executeInternal(JobDataMap jobDataMap) {
        bookRepository.saveAndFlush(new Book(TITLE));
    }
}
