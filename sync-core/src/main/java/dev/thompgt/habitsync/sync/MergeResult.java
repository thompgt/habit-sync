package dev.thompgt.habitsync.sync;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of merging one {@link Change} into one {@link EntityRecord}: the resulting
 * state, plus a decision record per contested register.
 *
 * @param state       the merged entity
 * @param resolutions one entry per register the change touched
 */
public record MergeResult(EntityRecord state, List<Resolution> resolutions) {

    public MergeResult {
        Objects.requireNonNull(state, "state");
        resolutions = List.copyOf(Objects.requireNonNull(resolutions, "resolutions"));
    }

    /**
     * Whether the merge altered anything.
     *
     * <p>Lets callers skip a database write for changes that are entirely superseded —
     * which is the common case when re-applying a duplicated or replayed page.
     */
    public boolean mutated() {
        return resolutions.stream().anyMatch(r -> r.verdict() == Resolution.Verdict.APPLIED);
    }

    /** Writes that were dropped because a newer value was already in place. */
    public List<Resolution> superseded() {
        return resolutions.stream().filter(Resolution::lostConflict).toList();
    }
}
