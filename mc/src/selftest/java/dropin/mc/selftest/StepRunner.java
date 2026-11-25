package dropin.mc.selftest;

import java.util.ArrayList;
import java.util.List;

/**
 * Tick-driven step runner for scripted self-tests (DESIGN §4.12). Development only.
 * Call {@link #tick()} once per client tick; each step is polled until it reports done,
 * throws, or exceeds its tick budget.
 */
public final class StepRunner {
    public enum State { RUNNING, PASSED, FAILED }

    @FunctionalInterface
    public interface Step {
        /** @return true when the step is complete; false to be polled again next tick. */
        boolean tick() throws Exception;
    }

    public record StepRecord(String name, int ticks, boolean ok) {}

    private record Named(String name, int timeoutTicks, Step step) {}

    private final List<Named> steps = new ArrayList<>();
    private final List<StepRecord> log = new ArrayList<>();
    private int index;
    private int ticksInStep;
    private State state = State.RUNNING;
    private String failure;

    public StepRunner step(String name, int timeoutTicks, Step step) {
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive: " + name);
        }
        steps.add(new Named(name, timeoutTicks, step));
        return this;
    }

    /** A step that runs once and is done. */
    public StepRunner action(String name, Action action) {
        return step(name, 1, () -> {
            action.run();
            return true;
        });
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    public State tick() {
        if (state != State.RUNNING) {
            return state;
        }
        if (index >= steps.size()) {
            state = State.PASSED;
            return state;
        }
        Named current = steps.get(index);
        ticksInStep++;
        boolean done;
        try {
            done = current.step.tick();
        } catch (Throwable t) {
            // The whole trace goes to the game log (stderr); result.json keeps the one-line reason.
            t.printStackTrace();
            return fail(current, "step '" + current.name + "' threw " + t);
        }
        if (done) {
            log.add(new StepRecord(current.name, ticksInStep, true));
            index++;
            ticksInStep = 0;
            if (index >= steps.size()) {
                state = State.PASSED;
            }
        } else if (ticksInStep >= current.timeoutTicks) {
            return fail(current, "step '" + current.name + "' timed out after " + ticksInStep + " ticks");
        }
        return state;
    }

    private State fail(Named current, String message) {
        log.add(new StepRecord(current.name, ticksInStep, false));
        failure = message;
        state = State.FAILED;
        return state;
    }

    /** Name of the step being polled, for diagnostics; null when finished. */
    public String currentStepName() {
        int i = index;
        return i < steps.size() ? steps.get(i).name : null;
    }

    public State state() {
        return state;
    }

    /** Null unless {@link State#FAILED}. */
    public String failure() {
        return failure;
    }

    public List<StepRecord> log() {
        return List.copyOf(log);
    }
}
