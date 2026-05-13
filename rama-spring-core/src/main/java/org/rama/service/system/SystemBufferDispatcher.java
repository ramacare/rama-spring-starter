package org.rama.service.system;

import org.rama.entity.system.SystemBuffer;

import java.util.List;

/**
 * Dispatches one buffer_type to its target system. Implementations register
 * themselves as Spring beans; the drain job selects the right one by matching
 * {@link #bufferType()}.
 */
public interface SystemBufferDispatcher {

    /** The buffer_type this dispatcher handles (e.g. "revision"). */
    String bufferType();

    /**
     * Dispatch a batch. On success, the drain job deletes the rows. On any
     * exception, the drain job updates the rows' attempt_count and last_error
     * but does NOT delete them — they're retried next run.
     */
    void dispatch(List<SystemBuffer> batch);
}
