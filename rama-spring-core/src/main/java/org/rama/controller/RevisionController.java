package org.rama.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.Revision;
import org.rama.repository.revision.RevisionRepository;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Controller
@RequiredArgsConstructor
@Slf4j
public class RevisionController {
    private final RevisionRepository revisionRepository;

    @QueryMapping
    public List<Revision> revisionByRevisionEntityAndMrn(@Argument String revisionEntity, @Argument String mrn) {
        return revisionRepository.findAllByRevisionEntityAndMrnOrderByRevisionDatetimeDesc(revisionEntity, mrn);
    }

    @QueryMapping
    public List<Revision> revisionByRevisionEntityInAndMrn(@Argument List<String> revisionEntityIn, @Argument String mrn) {
        return revisionRepository.findAllByRevisionEntityInAndMrnOrderByRevisionDatetimeDesc(revisionEntityIn, mrn);
    }

    @QueryMapping
    public List<Revision> revisionByRevisionKey(@Argument String revisionKey) {
        return revisionRepository.findAllByRevisionKeyOrderByRevisionDatetimeDesc(revisionKey);
    }
}
