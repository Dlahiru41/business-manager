package com.bmp.shared.context;

/**
 * A thread-local context binding made by a servlet filter. Closing it restores the previous value; always use
 * try-with-resources so the context never leaks to the next request served by the same thread.
 */
@FunctionalInterface
public interface ContextBinding extends AutoCloseable {

    @Override
    void close();
}
