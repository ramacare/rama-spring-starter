package org.rama.graphql;

import graphql.ExecutionInput;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.values.InputInterceptor;
import graphql.execution.values.legacycoercing.LegacyCoercingInputInterceptor;

/**
 * Restores the pre–graphql-java 22 lenient coercion behavior for the built-in
 * {@code Boolean} / {@code Float} / {@code Int} / {@code String} scalars.
 *
 * <p>graphql-java 22 tightened coercion: a numeric literal sent against a
 * {@code String!} variable, or a string {@code "1"} sent against {@code Int!},
 * now hard-fails with messages like:
 *
 * <pre>{@code Variable 'foo' has an invalid value: Expected a String input, but it was a 'Integer'}</pre>
 *
 * Clients written against the pre-Spring-Boot-4 stack relied on the lenient
 * {@code toString} / {@code parseInt} fallbacks. graphql-java's official
 * migration hook for this is
 * {@link LegacyCoercingInputInterceptor#migratesValues()} — it converts only
 * values that would have coerced under the legacy rules and leaves spec-clean
 * values untouched. {@code ValuesResolver} looks it up via
 * {@code graphqlContext.get(InputInterceptor.class)}, so wiring it from an
 * {@code Instrumentation}'s {@code instrumentExecutionInput} is sufficient.
 *
 * <p>Off by default in the starter (see {@code rama.graphql.legacy-coercion.enabled});
 * consumers like {@code ramaservice} and {@code his-service} previously carried
 * an identical shim under their own {@code config/GraphQlStringCoercionConfig}
 * — issue #27 moved the canonical copy here so they can drop the duplicate and
 * just set the property.</p>
 */
public final class LegacyScalarCoercionInstrumentation extends SimplePerformantInstrumentation {

    private static final InputInterceptor LEGACY_INTERCEPTOR =
            LegacyCoercingInputInterceptor.migratesValues();

    @Override
    public ExecutionInput instrumentExecutionInput(
            ExecutionInput executionInput,
            InstrumentationExecutionParameters parameters,
            InstrumentationState state) {
        executionInput.getGraphQLContext().put(InputInterceptor.class, LEGACY_INTERCEPTOR);
        return executionInput;
    }
}
