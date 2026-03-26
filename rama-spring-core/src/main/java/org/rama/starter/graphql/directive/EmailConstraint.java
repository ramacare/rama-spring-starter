package org.rama.starter.graphql.directive;

public class EmailConstraint extends AbstractPredefinedPatternConstraint {
    public EmailConstraint() {
        super(
                "Email",
                "^$|^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
        );
    }
}
