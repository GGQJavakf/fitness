package com.aifitness.assistant.plan.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

class JdbcPlanCandidateStoreTest {

    private static final AuthenticatedUserId USER = new AuthenticatedUserId(
            UUID.fromString("00000000-0000-0000-0000-000000000101"));

    @Test
    void serializesForOneInstanceAndReadsThroughAnotherUsingUserAndExpiryPredicates()
            throws Exception {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        PlanCandidateService.CandidateEnvelope candidate = candidate();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        stubUserLock(jdbc);
        JdbcPlanCandidateStore writer = new JdbcPlanCandidateStore(jdbc, json, 2);

        writer.save(USER, candidate);

        verify(jdbc).query(contains("FROM user_account"), any(RowMapper.class), any(Object[].class));
        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> updateParameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(updateSql.capture(), updateParameters.capture());
        assertThat(updateSql.getValue()).contains(
                "INSERT INTO plan_candidate",
                "AS incoming",
                "ON DUPLICATE KEY UPDATE",
                "candidate_json = incoming.candidate_json");
        String persistedJson = (String) updateParameters.getValue()[2];
        assertThat(persistedJson).contains(
                "\"expiresAt\":\"2026-09-01T09:00:00.123456Z\"");
        ResultSet row = mock(ResultSet.class);
        when(row.getString("candidate_json")).thenReturn(persistedJson);
        stubQuery(jdbc, row);
        JdbcPlanCandidateStore reader = new JdbcPlanCandidateStore(jdbc, json, 2);

        assertThat(reader.find(USER, candidate.candidateId())).contains(candidate);

        verify(jdbc).query(argThat(sql -> sql.contains("FROM plan_candidate")
                        && sql.contains("user_id = ? AND candidate_id = ?")
                        && sql.contains("expires_at > UTC_TIMESTAMP(6)")),
                any(RowMapper.class), any(Object[].class));
        assertThat(reader.find(USER, "not-a-uuid")).isEmpty();
    }

    @Test
    void enforcesTheSharedPerUserCapacity() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
        stubUserLock(jdbc);
        JdbcPlanCandidateStore store = new JdbcPlanCandidateStore(jdbc, json, 2);

        store.save(USER, candidate());

        verify(jdbc).query(contains("FROM user_account"), any(RowMapper.class), any(Object[].class));
        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(
                updateSql.capture(), any(Object[].class));
        assertThat(updateSql.getAllValues().get(1)).contains(
                "WHERE user_id = ?",
                "ORDER BY expires_at ASC",
                "LIMIT ?");
    }

    @Test
    void purgesExpiredRowsIndependentlyOfCandidateWrites() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        JdbcPlanCandidateStore store = new JdbcPlanCandidateStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), 2);

        store.purgeExpired();

        verify(jdbc).update("DELETE FROM plan_candidate WHERE expires_at <= UTC_TIMESTAMP(6)");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubQuery(JdbcOperations jdbc, ResultSet row) {
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubUserLock(JdbcOperations jdbc) {
        when(jdbc.query(contains("FROM user_account"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new byte[16]));
    }

    private static PlanCandidateService.CandidateEnvelope candidate() {
        return new PlanCandidateService.CandidateEnvelope(
                "00000000-0000-0000-0000-000000000201",
                PlanCandidateService.GenerationSource.FALLBACK_RULE_PLAN,
                new PlanDraft(
                        "TEST",
                        "Shared candidate",
                        List.of(new PlanDraft.Day(
                                "DAY_1",
                                "Day 1",
                                List.of(new PlanDraft.Exercise(
                                        "SQUAT",
                                        3,
                                        8,
                                        12,
                                        90,
                                        PlanDraft.WeightStatus.NEEDS_CALIBRATION)))),
                        Map.of()),
                new RuleReference("rules", "templates", "exercises"),
                PlanCandidateService.ExplanationStatus.DEGRADED,
                "fallback",
                Instant.parse("2026-09-01T09:00:00.123456Z").truncatedTo(ChronoUnit.MICROS));
    }
}
