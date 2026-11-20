package com.baari.eta;

import com.baari.queue.PublicTokenView;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * The patient's token page: the queue module's view plus the wait estimate. The token's fields stay
 * at the top level of the JSON, so pages and clients read them as before.
 *
 * @param eta null unless the patient is waiting, the queue is running and a doctor is serving
 */
public record PatientTokenView(@JsonUnwrapped PublicTokenView token, EtaDisplay eta) {}
