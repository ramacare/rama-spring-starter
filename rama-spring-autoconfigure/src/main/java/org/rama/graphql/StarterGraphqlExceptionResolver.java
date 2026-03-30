package org.rama.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;

import java.util.Collections;
import java.util.List;

public class StarterGraphqlExceptionResolver extends DataFetcherExceptionResolverAdapter {
    private final Environment environment;

    public StarterGraphqlExceptionResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    protected GraphQLError resolveToSingleError(@NotNull Throwable ex, @NotNull DataFetchingEnvironment env) {
        List<GraphQLError> errors = resolveToMultipleErrors(ex, env);
        if (errors != null && !errors.isEmpty()) {
            return errors.get(0);
        }
        return null;
    }

    @Override
    protected List<GraphQLError> resolveToMultipleErrors(@NotNull Throwable ex, @NotNull DataFetchingEnvironment env) {
        List<GraphQLError> customErrors = resolveCustomErrors(ex, env);
        if (customErrors != null && !customErrors.isEmpty()) {
            return customErrors;
        }

        if (ex instanceof Exception) {
            return Collections.singletonList(GraphqlErrorBuilder.newError()
                    .errorType(ErrorType.INTERNAL_ERROR)
                    .message(buildErrorMessage(ex))
                    .path(env.getExecutionStepInfo().getPath())
                    .location(env.getField().getSourceLocation())
                    .build());
        }
        return null;
    }

    protected List<GraphQLError> resolveCustomErrors(@NotNull Throwable ex, @NotNull DataFetchingEnvironment env) {
        return null;
    }

    protected String buildErrorMessage(Throwable ex) {
        String message = ex.getMessage();
        StringBuilder detailedMessage = new StringBuilder();

        if (environment.acceptsProfiles(Profiles.of("dev", "local"))) {
            detailedMessage.append("Detailed Exception Information:\n");
            detailedMessage.append("Exception Message: ").append(message).append("\n");
            detailedMessage.append("Exception Type: ").append(ex.getClass().getName()).append("\n");

            StackTraceElement[] stackTrace = ex.getStackTrace();
            if (stackTrace.length > 0) {
                detailedMessage.append("Stack Trace (Top 5 Elements):\n");
                for (int i = 0; i < Math.min(5, stackTrace.length); i++) {
                    StackTraceElement element = stackTrace[i];
                    detailedMessage.append(String.format(
                            "   at %s.%s(%s:%d)%n",
                            element.getClassName(),
                            element.getMethodName(),
                            element.getFileName(),
                            element.getLineNumber()
                    ));
                }
            } else {
                detailedMessage.append("Stack Trace: Not available\n");
            }
        } else {
            detailedMessage.append(message);
        }

        return detailedMessage.toString();
    }
}
