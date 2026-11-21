package com.baari.queue;

import java.util.List;
import java.util.UUID;

/**
 * Published (in-process, inside the purging transaction) when tokens lose their personal data to
 * the retention job (PLAN §9.4). Modules holding more personal data about these tokens (the
 * notification module's Telegram links) delete theirs in the same transaction.
 */
public record PiiPurged(List<UUID> tokenIds) {

    public PiiPurged {
        tokenIds = List.copyOf(tokenIds);
    }
}
