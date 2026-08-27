package org.rama.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the invariant that starter#36 restored: every entry point into
 * {@link GenericEntityService} must run in a transaction.
 *
 * <p>The {@code Map}-based overloads delegate to their {@code ID}-based siblings
 * through {@code this}, which bypasses the CGLIB proxy. That is safe only while
 * <em>both</em> ends carry {@code @Transactional} — if the outer method loses its
 * annotation, the delegation silently runs untransacted again. Nothing checked that
 * invariant before, which is how the bug was introduced the first time and why it
 * stayed latent for months: applications on Boot's default {@code open-in-view=true}
 * were shielded by the request-scoped {@code EntityManager}, and Mockito tests invoke
 * the bare object, so no proxy and no transaction manager are involved at all.
 *
 * <p>Resolving the attribute through {@link AnnotationTransactionAttributeSource} —
 * rather than looking for a literal annotation — is what makes this generalise: it
 * accounts for class-level declarations, meta-annotations and inherited ones, exactly
 * as the runtime proxy does. See starter#42.
 *
 * <h2>Why this covers only {@code GenericEntityService}</h2>
 *
 * <p>starter#42 asked whether to extend the assertion to the other nine
 * {@code @Transactional}-bearing services. Deliberately not, because the rule enforced
 * here — <em>every public instance method must be transactional</em> — is only true of
 * this class. {@code GenericEntityService} is a pure mutation facade whose entire
 * read-only surface is static, so "instance method" and "mutation" coincide. Every other
 * service has a mixed surface: {@code RevisionService} declares one transactional method
 * out of eight public ones, {@code SystemParameterService} one of four,
 * {@code MasterIdService} five of seven. Applying the same assertion there would need a
 * hand-maintained list of read methods that would rot with every added getter, and would
 * encode no invariant — a test that reads as coverage while checking nothing.
 *
 * <p>The bug class itself is not confined to this file, so if it turns up elsewhere the
 * answer is a targeted test of the same shape in that service, not a package-wide sweep.
 */
@Tag("unit")
class GenericEntityServiceTransactionalTest {

    /**
     * Public <em>instance</em> methods that are deliberately non-transactional.
     *
     * <p>Empty, and that is the point: the check is fail-closed, so a new entry point
     * is required to be transactional unless someone adds it here on purpose. Adding a
     * name should come with a one-line reason, and
     * {@link #exemptions_stillMatchAnExistingMethod()} keeps the list from outliving
     * the methods it names.
     *
     * <p>Static methods are excluded structurally rather than listed here — see
     * {@link #everyPublicInstanceMethod_resolvesATransactionAttribute()}.
     */
    private static final Set<String> NON_TRANSACTIONAL_BY_DESIGN = Set.of();

    @Test
    void everyPublicInstanceMethod_resolvesATransactionAttribute() {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();

        List<String> missing = publicInstanceMethods()
                .filter(m -> !NON_TRANSACTIONAL_BY_DESIGN.contains(m.getName()))
                .filter(m -> source.getTransactionAttribute(m, GenericEntityService.class) == null)
                .map(Method::toString)
                .sorted()
                .toList();

        assertThat(missing)
                .as("public instance methods with no transaction attribute — a Map-based overload "
                        + "delegating to its ID-based sibling through `this` runs untransacted. "
                        + "See starter#36, #42")
                .isEmpty();
    }

    @Test
    void exemptions_stillMatchAnExistingMethod() {
        Set<String> declared = publicInstanceMethods().map(Method::getName).collect(java.util.stream.Collectors.toSet());

        assertThat(declared)
                .as("stale entries in NON_TRANSACTIONAL_BY_DESIGN silently weaken the guard")
                .containsAll(NON_TRANSACTIONAL_BY_DESIGN);
    }

    /**
     * The read-only surface — the {@code findEntityPageable} and
     * {@code batchMappingRelation} families — is {@code static}, so it is skipped here
     * rather than named in an exemption list. That is a stronger statement than "these
     * happen to be reads": a static method cannot be intercepted by a Spring proxy at
     * all, so the invariant this test enforces cannot apply to one. It also means a new
     * static helper needs no edit here, while a new <em>instance</em> method cannot slip
     * through by being added to a list.
     */
    private static java.util.stream.Stream<Method> publicInstanceMethods() {
        return Arrays.stream(GenericEntityService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .filter(m -> !m.isBridge());
    }
}
