package com.baari.clinic;

import com.baari.clinic.internal.StaffUserRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way a password hash leaves the clinic module: the identity module uses it to check a
 * login. It needs no principal because nobody is logged in yet.
 */
@Service
public class StaffAccountLookup {

    private final StaffUserRepository staff;

    StaffAccountLookup(StaffUserRepository staff) {
        this.staff = staff;
    }

    @Transactional(readOnly = true)
    public Optional<StaffAccount> findForLogin(String username) {
        return staff.findByUsername(username).map(u -> new StaffAccount(u.toView(), u.getPasswordHash()));
    }
}
