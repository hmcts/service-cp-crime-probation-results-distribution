package uk.gov.hmcts.testfixtures;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal resend-leg route handler for the authorisation filter integration test. */
@RestController
public class ResendLegAuthorisationTestController {

    @PostMapping("/operations/resend-leg")
    ResponseEntity<Void> resendLeg() {
        return ResponseEntity.accepted().build();
    }
}
