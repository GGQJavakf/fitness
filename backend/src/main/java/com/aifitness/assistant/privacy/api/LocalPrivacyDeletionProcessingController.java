package com.aifitness.assistant.privacy.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local-only worker hook. Experience and production profiles must process deletion out of band. */
@RestController
@RequestMapping("/api/v1/privacy")
@Profile({"local", "test"})
public final class LocalPrivacyDeletionProcessingController {

    private final PrivacyRequestService privacy;
    private final PrivacyDeletionWorker deletionWorker;
    private final Clock clock;

    public LocalPrivacyDeletionProcessingController(
            PrivacyRequestService privacy, PrivacyDeletionWorker deletionWorker, Clock clock) {
        this.privacy = privacy;
        this.deletionWorker = deletionWorker;
        this.clock = clock;
    }

    @PostMapping("/deletion-requests/{id}/process")
    public ApiResponse<PrivacyController.DeletionRequestData> processDeletionRequest(
            AuthenticatedUserId user,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Reauthentication-Proof", required = false) String proof,
            @RequestHeader(value = "X-Local-Deletion-Approval", required = false) String approval) {
        privacy.authorizeDeletionProcessing(user, id, proof);
        boolean approved = "LOCAL_TEST_APPROVED".equals(approval);
        return response(PrivacyController.DeletionRequestData.from(
                deletionWorker.process(id, approved)));
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }
}
