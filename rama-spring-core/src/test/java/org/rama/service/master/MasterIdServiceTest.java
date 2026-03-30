package org.rama.service.master;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.master.MasterId;
import org.rama.repository.master.MasterIdRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MasterIdServiceTest {

    @Mock
    private MasterIdRepository masterIdRepository;

    @InjectMocks
    private MasterIdService masterIdService;

    @Captor
    private ArgumentCaptor<MasterId> masterIdCaptor;

    @Test
    void issue_shouldGenerateIdWithPrefix() {
        String prefixPattern = "'TEST'yyMMdd";
        String expectedPrefix = new SimpleDateFormat(prefixPattern).format(new Date());

        when(masterIdRepository.findFirstByIdTypeAndPrefix("MRN", expectedPrefix)).thenReturn(null);
        when(masterIdRepository.save(any(MasterId.class))).thenAnswer(inv -> inv.getArgument(0));

        String result = masterIdService.issue("MRN", prefixPattern, 5, "", true);

        assertThat(result).startsWith(expectedPrefix);
    }

    @Test
    void issue_shouldIncrementRunningNumber() {
        String prefixPattern = "'P'yyMMdd";
        String expectedPrefix = new SimpleDateFormat(prefixPattern).format(new Date());

        MasterId existing = new MasterId();
        existing.setIdType("MRN");
        existing.setPrefix(expectedPrefix);
        existing.setRunningNumber(41);

        when(masterIdRepository.findFirstByIdTypeAndPrefix("MRN", expectedPrefix)).thenReturn(existing);
        when(masterIdRepository.save(any(MasterId.class))).thenAnswer(inv -> inv.getArgument(0));

        String result = masterIdService.issue("MRN", prefixPattern, 5, "", false);

        assertThat(result).isEqualTo(expectedPrefix + "00042");
        assertThat(existing.getRunningNumber()).isEqualTo(42);
    }

    @Test
    void issue_shouldApplyLuhnCheckDigit() {
        String prefixPattern = "'X'";
        String expectedPrefix = "X";

        when(masterIdRepository.findFirstByIdTypeAndPrefix("TEST", expectedPrefix)).thenReturn(null);
        when(masterIdRepository.save(any(MasterId.class))).thenAnswer(inv -> inv.getArgument(0));

        String result = masterIdService.issue("TEST", prefixPattern, 3, "", true);

        assertThat(result).startsWith("X001");
        assertThat(result.length()).isGreaterThan("X001".length());
    }

    @Test
    void issue_shouldCreateNewMasterIdWhenNoneExists() {
        String prefixPattern = "'N'";
        when(masterIdRepository.findFirstByIdTypeAndPrefix("TYPE", "N")).thenReturn(null);
        when(masterIdRepository.save(any(MasterId.class))).thenAnswer(inv -> inv.getArgument(0));

        masterIdService.issue("TYPE", prefixPattern, 4, "", false);

        verify(masterIdRepository).save(masterIdCaptor.capture());
        MasterId saved = masterIdCaptor.getValue();
        assertThat(saved.getIdType()).isEqualTo("TYPE");
        assertThat(saved.getPrefix()).isEqualTo("N");
        assertThat(saved.getRunningNumber()).isEqualTo(1);
    }

    @Test
    void resetRunningNumbers_shouldResetExistingPrefixes() {
        MasterId existing = new MasterId();
        existing.setRunningNumber(50);

        when(masterIdRepository.findFirstByIdTypeAndPrefix("serviceNo", "A")).thenReturn(existing);
        when(masterIdRepository.findFirstByIdTypeAndPrefix("serviceNo", "B")).thenReturn(null);
        when(masterIdRepository.save(any(MasterId.class))).thenAnswer(inv -> inv.getArgument(0));

        int touched = masterIdService.resetRunningNumbers("serviceNo", List.of("A", "B"));

        assertThat(touched).isEqualTo(1);
        assertThat(existing.getRunningNumber()).isEqualTo(0);
    }

    @Test
    void issueNoCheckDigit_shouldNotAppendCheckDigit() {
        String prefixPattern = "'Z'";
        when(masterIdRepository.findFirstByIdTypeAndPrefix("TYPE", "Z")).thenReturn(null);
        when(masterIdRepository.save(any(MasterId.class))).thenAnswer(inv -> inv.getArgument(0));

        String result = masterIdService.issueNoCheckDigit("TYPE", prefixPattern, 3);

        assertThat(result).isEqualTo("Z001");
    }
}
