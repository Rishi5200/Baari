package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/**
 * A queue in rotating-QR mode was joined without a current code: the link is older than about two
 * minutes, or was never signed (PLAN §9.3). The patient scans the code on the clinic's screen again.
 */
public class QrExpiredException extends DomainException {

    public QrExpiredException() {
        super(
                "qr-expired",
                HttpStatus.FORBIDDEN,
                "This QR code has expired. Please scan the code on the clinic's screen again.");
    }
}
