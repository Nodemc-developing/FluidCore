package com.ydxc20091.fluidcore.api;

/** Participants expose local snapshots; effects outside the snapshot belong in afterCommit(). */
public interface TransactionParticipant {
    Object snapshot();
    void restore(Object snapshot);
    void validate();
    void afterCommit();

    /** Override when validation must compare a revision or tick captured on enlistment. */
    default void validateSnapshot(Object snapshot) { validate(); }
}
