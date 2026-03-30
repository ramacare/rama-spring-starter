package org.rama.graphql.directive;

import graphql.GraphQLError;
import graphql.Scalars;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLInputType;
import graphql.validation.constraints.AbstractDirectiveConstraint;
import graphql.validation.constraints.Documentation;
import graphql.validation.rules.ValidationEnvironment;
import lombok.Getter;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static graphql.schema.GraphQLTypeUtil.isList;
import static java.util.Collections.emptyList;

@Getter
public abstract class AbstractPredefinedPatternConstraint extends AbstractDirectiveConstraint {
    private final Pattern pattern;

    protected AbstractPredefinedPatternConstraint(String name, String regex) {
        super(name);
        this.pattern = Pattern.compile(regex);
    }

    @Override
    public Documentation getDocumentation() {
        return Documentation.newDocumentation()
                .messageTemplate(getMessageTemplate())
                .description("The String must match the specified pattern (" + getName() + ").")
                .example("updateDriver( licencePlate : String @" + getName() + " : DriverDetails")
                .applicableTypeNames(Scalars.GraphQLString.getName(), Scalars.GraphQLID.getName(), "Lists")
                .directiveSDL(
                        "directive @" + getName() + "(message : String = \"%s\") on ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION",
                        getMessageTemplate()
                )
                .build();
    }

    @Override
    public boolean appliesToType(GraphQLInputType inputType) {
        return isStringOrID(inputType) || isList(inputType);
    }

    @Override
    protected List<GraphQLError> runConstraint(ValidationEnvironment validationEnvironment) {
        Matcher matcher = pattern.matcher(String.valueOf(validationEnvironment.getValidatedValue()));
        if (!matcher.matches()) {
            return mkError(validationEnvironment, "pattern", getName());
        }
        return emptyList();
    }

    @Override
    protected boolean appliesToListElements() {
        return true;
    }

    @Override
    protected String getMessageTemplate(GraphQLAppliedDirective directive) {
        String msg = null;
        GraphQLAppliedDirectiveArgument arg = directive.getArgument("message");
        if (arg != null) {
            msg = arg.getValue();
        }
        return msg == null ? getDefaultMessageTemplate() : msg;
    }

    protected String getDefaultMessageTemplate() {
        return "{path} must match \"{pattern}\" pattern.";
    }
}
