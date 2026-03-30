package org.rama.controller.system;

import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.system.QSystemLog;
import org.rama.entity.system.SystemLog;
import org.rama.repository.system.SystemLogRepository;
import org.rama.util.QueryUtil;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class SystemLogController {
    private final SystemLogRepository systemLogRepository;

    @QueryMapping
    public PageableDTO<SystemLog> systemLogByExamplePageable(@Argument Map<String, Object> example, @Argument PageableInput pageable) {
        QSystemLog qSystemLog = QSystemLog.systemLog;
        BooleanExpression predicate = QueryUtil.Example(example, qSystemLog);

        return PageableDTO.of(systemLogRepository.findAll(predicate,pageable.toPageRequest()));
    }
}
