package dev.thompgt.habitsync.auth;

/**
 * Any failure to authenticate.
 *
 * <p>One exception type for every cause — unknown email, wrong password, expired or
 * reused refresh token — because the client is told the same thing regardless. The
 * specific reason is logged server-side; returning it would let an attacker enumerate
 * accounts and probe token state.
 */
public class AuthenticationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
