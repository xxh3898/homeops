package dev.homeops.agent.control;

import dev.homeops.agent.ContainerControlAuthority.DecisionCode;

public final class ContainerControlDeniedException extends RuntimeException {

    private final DecisionCode decisionCode;

    public ContainerControlDeniedException(DecisionCode decisionCode) {
        super("Container control target is not eligible");
        this.decisionCode = decisionCode;
    }

    public DecisionCode decisionCode() {
        return decisionCode;
    }
}
