package uk.gov.hmcts.testfixtures;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal replay route handler for the authorisation filter integration test. */
@RestController
public class ReplayAuthorisationTestController {

    @PostMapping("/operations/requests/{requestId}/replay")
    ResponseEntity<Void> replay() {
        return ResponseEntity.ok().build();
    }
}
