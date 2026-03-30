package org.rama.service.master;

import org.apache.commons.validator.routines.checkdigit.CheckDigitException;
import org.apache.commons.validator.routines.checkdigit.LuhnCheckDigit;
import org.rama.entity.master.MasterId;
import org.rama.repository.master.MasterIdRepository;
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
            MasterId created = new MasterId();
            created.setIdType(idType);
            created.setPrefix(prefix);
            created.setRunningNumber(runningNumber);
            masterIdRepository.save(created);
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
