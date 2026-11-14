package com.baari.clinic;

/** A staff user plus their password hash, for the login check only. */
public record StaffAccount(StaffUserView user, String passwordHash) {

    @Override
    public String toString() {
        return "StaffAccount[user=" + user + "]";
    }
}
