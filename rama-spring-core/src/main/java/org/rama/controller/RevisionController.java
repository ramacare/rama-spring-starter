package org.rama.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.Revision;
import org.rama.service.RevisionService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
@Slf4j
public class RevisionController {
    private final RevisionService revisionService;

    @QueryMapping
    public List<Revision> revisionByRevisionEntityAndMrn(@Argument String revisionEntity, @Argument String mrn) {
        return revisionService.findByEntityAndMrn(revisionEntity, mrn);
    }

    @QueryMapping
    public List<Revision> revisionByRevisionEntityInAndMrn(@Argument List<String> revisionEntityIn, @Argument String mrn) {
        return revisionService.findByEntityInAndMrn(revisionEntityIn, mrn);
    }

    @QueryMapping
    public List<Revision> revisionByRevisionKey(@Argument String revisionKey) {
        return revisionService.findHistory(revisionKey);
    }
}
