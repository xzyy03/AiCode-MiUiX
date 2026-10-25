package com.termux.terminal;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Abstracts the I/O backend of a {@link TerminalSession}: either a local pty subprocess
 * (JNI fork, used by the local PRoot container) or a remote byte stream (SSH shell channel,
 * used by the remote execution mode).
 *
 * <p>A backend owns the transport and exposes:
 * <ul>
 *   <li>{@link #getInputStream()} / {@link #getOutputStream()} for bidirectional bytes;</li>
 *   <li>{@link #resize(int, int)} to propagate terminal size changes;</li>
 *   <li>{@link #waitForExit()} to block until the backend's process/stream ends;</li>
 *   <li>{@link #close()} to release resources.</li>
 * </ul>
 *
 * <p>The {@link TerminalSession} reader/writer/waiter threads drive these streams exactly as
 * they drove the pty file descriptor in upstream Termux, so terminal emulation behavior is
 * unchanged regardless of backend.
 */
public interface SessionBackend {

    /** Bytes flowing from the backend (subprocess stdout / remote shell output) toward the emulator. */
    InputStream getInputStream();

    /** Bytes flowing from the emulator (user input) toward the backend (subprocess stdin / remote shell input). */
    OutputStream getOutputStream();

    /** Propagate a terminal resize to the backend (pty window size / remote PTY size). */
    void resize(int columns, int rows);

    /**
     * Block the calling thread until the backend's process/stream has ended.
     *
     * @return the exit status if available (>= 0 exit code, < 0 negated signal), or 0 if
     *         the backend has no meaningful exit status (e.g. a closed remote stream).
     */
    int waitForExit();

    /** Release the backend's resources (close pty fd / remote shell channel). Safe to call multiple times. */
    void close();
}
