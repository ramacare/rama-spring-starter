package org.rama.service;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class TransactionRunnerService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runInNewTx(Runnable r) {
        r.run();
    }
}
