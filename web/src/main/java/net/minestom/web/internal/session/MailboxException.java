package net.minestom.web.internal.session;

/// Mailbox-related failure thrown by [Session]. The [Reason] maps to an HTTP status the
/// dashboard surfaces directly.
public final class MailboxException extends RuntimeException {
    public enum Reason {
        /// Inbox at capacity or worker stopped — HTTP 503.
        BUSY(503, "session mailbox busy"),
        /// Owner thread didn't finish within the caller's timeout — HTTP 504.
        TIMEOUT(504, "session mailbox timeout");

        final int status;
        final String label;

        Reason(int status, String label) {
            this.status = status;
            this.label = label;
        }
    }

    private final Reason reason;

    public MailboxException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public int httpStatus() { return reason.status; }

    public String httpMessage() { return reason.label + ": " + getMessage(); }
}
