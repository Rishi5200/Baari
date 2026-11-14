package com.baari.common.crypto;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/** The phone number can't be read as a valid number. Never includes the number itself. */
public class InvalidPhoneNumberException extends DomainException {

    public InvalidPhoneNumberException() {
        super(
                "invalid-phone",
                HttpStatus.BAD_REQUEST,
                "Enter a valid 10-digit mobile number, or include the country code");
    }
}
