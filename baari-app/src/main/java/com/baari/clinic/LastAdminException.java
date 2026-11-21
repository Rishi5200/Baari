package com.baari.clinic;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The change would leave a clinic without an active clinic admin, or an admin is locking themselves
 * out (ADR 0009 item 1). A clinic with no admin cannot fix itself.
 */
public class LastAdminException extends DomainException {

    public LastAdminException(String message) {
        super("last-admin", HttpStatus.CONFLICT, message);
    }
}
