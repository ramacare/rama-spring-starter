package org.rama.graphql.instrumentation;

import graphql.ExecutionInput;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.language.Document;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LenientStringVariableInstrumentationTest {

    private final LenientStringVariableInstrumentation instrumentation = new LenientStringVariableInstrumentation();

    @Test
    void integerVariableForStringArgumentIsCoercedToString() {
        String query = "mutation ($dispensingReference: String!, $dispensingType: String!) { x(dispensingReference: $dispensingReference, dispensingType: $dispensingType) { id } }";
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(Map.of("dispensingReference", 2792, "dispensingType", "prescription"))
                .build();

        ExecutionInput coerced = instrumentation
                .instrumentExecutionInput(input, (InstrumentationExecutionParameters) null, null);

        assertThat(coerced.getRawVariables().toMap())
                .containsEntry("dispensingReference", "2792")
                .containsEntry("dispensingType", "prescription");
    }

    @Test
    void booleanVariableForStringArgumentIsCoercedToString() {
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query("query ($flag: String) { x(flag: $flag) }")
                .variables(Map.of("flag", true))
                .build();

        ExecutionInput coerced = instrumentation
                .instrumentExecutionInput(input, (InstrumentationExecutionParameters) null, null);

        assertThat(coerced.getRawVariables().toMap()).containsEntry("flag", "true");
    }

    @Test
    void integerVariableForIntArgumentIsLeftAlone() {
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query("query ($id: Int!) { patient(id: $id) { mrn } }")
                .variables(Map.of("id", 2792))
                .build();

        ExecutionInput coerced = instrumentation
                .instrumentExecutionInput(input, (InstrumentationExecutionParameters) null, null);

        assertThat(coerced.getRawVariables().toMap()).containsEntry("id", 2792);
    }

    @Test
    void stringVariableIsPassedThrough() {
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query("query ($name: String!) { patient(name: $name) { mrn } }")
                .variables(Map.of("name", "alice"))
                .build();

        ExecutionInput coerced = instrumentation
                .instrumentExecutionInput(input, (InstrumentationExecutionParameters) null, null);

        assertThat(coerced.getRawVariables().toMap()).containsEntry("name", "alice");
    }

    @Test
    void emptyVariablesShortCircuits() {
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query("{ __typename }")
                .build();

        ExecutionInput coerced = instrumentation
                .instrumentExecutionInput(input, (InstrumentationExecutionParameters) null, null);

        assertThat(coerced).isSameAs(input);
    }

    @Test
    void collectStringTypedVariableNamesIgnoresNonStringTypes() {
        Document doc = new Parser().parseDocument(
                "mutation ($ref: String!, $type: String!, $page: Int, $tags: [String]) { x }");
        Set<String> names = LenientStringVariableInstrumentation.collectStringTypedVariableNames(doc);
        // [String] is deliberately excluded — list element coercion would need
        // different handling than scalar variables.
        assertThat(names).containsExactlyInAnyOrder("ref", "type");
    }
}
