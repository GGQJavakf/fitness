package com.aifitness.assistant.privacy.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import com.aifitness.assistant.privacy.application.ReauthenticationProofIssuer;
import com.aifitness.assistant.privacy.application.PrivacyExportRepository;
import com.aifitness.assistant.privacy.domain.DeletionRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/privacy")
@Profile({"local", "test"})
public final class PrivacyController {

    private final PrivacyRequestService privacy;
    private final Clock clock;
    private final PrivacyDeletionWorker deletionWorker;
    private final ReauthenticationProofIssuer proofIssuer;

    public PrivacyController(
            PrivacyRequestService privacy,
            Clock clock,
            PrivacyDeletionWorker deletionWorker,
            ReauthenticationProofIssuer proofIssuer) {
        this.privacy = privacy;
        this.clock = clock;
        this.deletionWorker = deletionWorker;
        this.proofIssuer = proofIssuer;
    }

    @PostMapping("/reauthentication-proofs")
    public ApiResponse<ReauthenticationProofData> issueReauthenticationProof(
            AuthenticatedUserId user, @RequestBody CreateReauthenticationProofRequest request) {
        return response(ReauthenticationProofData.from(proofIssuer.issue(user, request.code())));
    }

    @GetMapping("/export")
    public ApiResponse<PrivacyExportData> export(
            AuthenticatedUserId user,
            @RequestHeader(value = "X-Reauthentication-Proof", required = false) String reauthenticationProof) {
        return response(PrivacyExportData.from(privacy.export(user, reauthenticationProof)));
    }

    @GetMapping("/exports/{id}")
    public ApiResponse<PrivacyExportData> getExport(
            AuthenticatedUserId user, @PathVariable UUID id) {
        return response(PrivacyExportData.from(privacy.getExport(user, id)));
    }

    @PostMapping("/deletion-requests")
    public ResponseEntity<ApiResponse<DeletionRequestData>> requestDeletion(
            AuthenticatedUserId user, @RequestBody CreateDeletionRequest request) {
        DeletionRequest created = privacy.requestDeletion(
                user, request.reauthenticationProof(), request.confirmationText());
        return ResponseEntity.status(HttpStatus.CREATED).body(response(DeletionRequestData.from(created)));
    }

    @GetMapping("/deletion-requests/{id}")
    public ApiResponse<DeletionRequestData> getDeletionRequest(
            AuthenticatedUserId user, @PathVariable UUID id) {
        return response(DeletionRequestData.from(privacy.getDeletionRequest(user, id)));
    }

    @PostMapping("/deletion-requests/{id}/process")
    public ApiResponse<DeletionRequestData> processDeletionRequest(
            AuthenticatedUserId user,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Reauthentication-Proof", required = false) String proof,
            @RequestHeader(value = "X-Local-Deletion-Approval", required = false) String approval) {
        privacy.authorizeDeletionProcessing(user, id, proof);
        boolean approved = "LOCAL_TEST_APPROVED".equals(approval);
        return response(DeletionRequestData.from(deletionWorker.process(id, approved)));
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record CreateDeletionRequest(String reauthenticationProof, String confirmationText) {}

    public record CreateReauthenticationProofRequest(String code) {}

    public record ReauthenticationProofData(String proof, Instant issuedAt, Instant expiresAt) {
        static ReauthenticationProofData from(ReauthenticationProofIssuer.IssuedProof issued) {
            return new ReauthenticationProofData(
                    issued.proof(), issued.issuedAt(), issued.expiresAt());
        }
    }

    public record PrivacyExportData(
            UUID id,
            String status,
            Instant generatedAt,
            Instant expiresAt,
            List<PrivacyResourceData> resources,
            List<String> scope,
            List<String> excludedRetentionCategories) {
        static PrivacyExportData from(PrivacyExportRepository.ExportArtifact export) {
            return new PrivacyExportData(
                    export.id(),
                    export.status(),
                    export.generatedAt(),
                    export.expiresAt(),
                    export.resources().stream()
                            .map(resource -> new PrivacyResourceData(
                                    resource.category().name(),
                                    resource.recordCount(),
                                    resource.records().stream()
                                            .map(record -> new PrivacyRecordData(
                                                    record.id(), record.summary()))
                                            .toList()))
                            .toList(),
                    export.scope(),
                    export.excludedRetentionCategories());
        }
    }

    public record PrivacyResourceData(
            String category, int recordCount, List<PrivacyRecordData> records) {}

    public record PrivacyRecordData(String id, String summary) {}

    public record DeletionRequestData(
            UUID id,
            DeletionRequest.Status status,
            Instant requestedAt,
            Instant updatedAt,
            List<String> deletionScope,
            List<String> retainedCategories) {
        static DeletionRequestData from(DeletionRequest request) {
            return new DeletionRequestData(
                    request.id(),
                    request.status(),
                    request.requestedAt(),
                    request.updatedAt(),
                    PrivacyRequestService.ordinaryDataCategories(),
                    PrivacyRequestService.requiredRetentionCategories());
        }
    }
}
