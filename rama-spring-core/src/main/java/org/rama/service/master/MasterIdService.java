package org.rama.service.master;

import org.apache.commons.validator.routines.checkdigit.CheckDigitException;
import org.apache.commons.validator.routines.checkdigit.LuhnCheckDigit;
import org.rama.entity.master.MasterId;
import org.rama.repository.master.MasterIdRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MasterIdService {
    private final MasterIdRepository masterIdRepository;
    private final LuhnCheckDigit checkDigit = new LuhnCheckDigit();

    public MasterIdService(MasterIdRepository masterIdRepository) {
        this.masterIdRepository = masterIdRepository;
    }

    protected MasterId findFirstByIdTypeAndPrefix(String idType, String prefix) {
        return masterIdRepository.findFirstByIdTypeAndPrefix(idType, prefix);
    }

    protected MasterId saveMasterId(MasterId masterId) {
        return masterIdRepository.save(masterId);
    }

    @Transactional
    public String issue(String idType, String prefixPattern, Integer numberLength, CharSequence spacerChar, boolean addCheckDigit) {
        String prefix = new SimpleDateFormat(prefixPattern).format(new Date());
        List<String> values = new ArrayList<>();
        values.add(prefix);

        MasterId masterId = findFirstByIdTypeAndPrefix(idType, prefix);
        int runningNumber = 1;

        if (masterId != null) {
            runningNumber = masterId.getRunningNumber() + 1;
            masterId.setRunningNumber(runningNumber);
            masterIdRepository.save(masterId);
        } else {
            try {
                MasterId created = new MasterId();
                created.setIdType(idType);
                created.setPrefix(prefix);
                created.setRunningNumber(runningNumber);
                masterIdRepository.saveAndFlush(created);
            } catch (DataIntegrityViolationException e) {
                masterId = findFirstByIdTypeAndPrefix(idType, prefix);
                if (masterId == null) {
                    throw e;
                }
                runningNumber = masterId.getRunningNumber() + 1;
                masterId.setRunningNumber(runningNumber);
                masterIdRepository.save(masterId);
            }
        }

        values.add(String.format("%0" + numberLength + "d", runningNumber));
        if (addCheckDigit) {
            try {
                values.add(checkDigit.calculate(String.valueOf(runningNumber)));
            } catch (CheckDigitException ignored) {
            }
        }
        return String.join(spacerChar, values);
    }

    @Transactional
    public String issue(String idType, String prefixPattern, Integer numberLength) {
        return issue(idType, prefixPattern, numberLength, "", true);
    }

    @Transactional
    public String issueNoCheckDigit(String idType, String prefixPattern, Integer numberLength) {
        return issue(idType, prefixPattern, numberLength, "", false);
    }

    /**
     * Atomically increments the running number for the given idType and prefix,
     * returning the new value. Throws if the current number has reached max.
     * Uses pessimistic locking via the repository to prevent race conditions.
     *
     * @return the new running number after increment
     * @throws IllegalStateException if the MasterId record does not exist or max is reached
     */
    @Transactional
    public int incrementRunningNumber(String idType, String prefix, int max) {
        MasterId masterId = findFirstByIdTypeAndPrefix(idType, prefix);
        if (masterId == null) {
            throw new IllegalStateException("No MasterId found for idType=" + idType + " prefix=" + prefix);
        }
        int current = masterId.getRunningNumber();
        if (current >= max) {
            throw new IllegalStateException("Running number reached max (" + max + ") for prefix=" + prefix);
        }
        int next = current + 1;
        masterId.setRunningNumber(next);
        masterIdRepository.save(masterId);
        return next;
    }

    @Transactional
    public int resetRunningNumbers(String idType, List<String> prefixes) {
        int touched = 0;
        for (String prefix : prefixes) {
            MasterId masterId = findFirstByIdTypeAndPrefix(idType, prefix);
            if (masterId != null) {
                masterId.setRunningNumber(0);
                masterIdRepository.save(masterId);
                touched++;
            }
        }
        return touched;
    }
}
