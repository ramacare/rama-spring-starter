package org.rama.graphql.instrumentation;

import graphql.ExecutionInput;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.NonNullType;
import graphql.language.OperationDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.VariableDefinition;
import graphql.parser.Parser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * graphql-java 22 tightened the built-in {@code String} scalar to reject non-String
 * raw values during variable coercion. Legacy clients that send numeric values for
 * {@code String!} arguments (e.g. {@code dispensingReference: 2792}) now fail with
 * "Variable 'x' has an invalid value: Expected a String input, but it was a 'Integer'".
 *
 * <p>The built-in scalar can't be swapped on the {@code RuntimeWiring} in
 * graphql-java 25 — the {@code SchemaGenerator} sees both the custom and the
 * built-in scalar and {@code GraphQLSchema.build()} fails its unique-names check
 * with "You have redefined the type 'String'".</p>
 *
 * <p>This instrumentation side-steps that by pre-coercing raw variables before
 * graphql-java validates them: when the query declares a variable as
 * {@code String} or {@code String!} and the raw value is a {@code Number},
 * {@code Boolean}, or {@code Character}, it is rewritten to its {@code toString}
 * representation. {@code [String]} / non-scalar types are left alone because
 * list-element coercion would need different handling.</p>
 */
public class LenientStringVariableInstrumentation extends SimplePerformantInstrumentation {

    private static final Parser PARSER = new Parser();

    @Override
    public ExecutionInput instrumentExecutionInput(
            ExecutionInput executionInput,
            InstrumentationExecutionParameters parameters,
            InstrumentationState state) {

        Map<String, Object> rawVars = executionInput.getRawVariables().toMap();
        if (rawVars.isEmpty()) {
            return executionInput;
        }

        Set<String> stringVars;
        try {
            Document document = PARSER.parseDocument(executionInput.getQuery());
            stringVars = collectStringTypedVariableNames(document);
        } catch (RuntimeException ex) {
            return executionInput;
        }

        if (stringVars.isEmpty()) {
            return executionInput;
        }

        Map<String, Object> coerced = null;
        for (String name : stringVars) {
            Object value = rawVars.get(name);
            if (value != null && !(value instanceof String) && isStringCoercible(value)) {
                if (coerced == null) {
                    coerced = new HashMap<>(rawVars);
                }
                coerced.put(name, value.toString());
            }
        }

        if (coerced == null) {
            return executionInput;
        }

        Map<String, Object> finalVars = coerced;
        return executionInput.transform(b -> b.variables(finalVars));
    }

    public static Set<String> collectStringTypedVariableNames(Document document) {
        Set<String> names = new HashSet<>();
        for (Definition<?> definition : document.getDefinitions()) {
            if (!(definition instanceof OperationDefinition op)) continue;
            for (VariableDefinition var : op.getVariableDefinitions()) {
                if ("String".equals(unwrapNamedType(var.getType()))) {
                    names.add(var.getName());
                }
            }
        }
        return names;
    }

    // Unwrap NonNullType / named types; return null for list/other composites so
    // we don't accidentally coerce inside [String] values — those need element-level
    // handling rather than scalar-variable rewriting.
    private static String unwrapNamedType(Type<?> type) {
        if (type instanceof NonNullType nn) return unwrapNamedType(nn.getType());
        if (type instanceof TypeName tn) return tn.getName();
        return null;
    }

    private static boolean isStringCoercible(Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof Character;
    }
}
