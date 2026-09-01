package com.aifitness.assistant.plan.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.plan.application.CandidateCommitReceiptStore;
import com.aifitness.assistant.plan.application.CandidateCommitService;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

class JdbcCandidateCommitReceiptStoreTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID VERSION = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final String KEY_DIGEST = "11".repeat(32);
    private static final String PAYLOAD_DIGEST = "22".repeat(32);

    @Test
    void claimsAnEmptyDigestBackedReceiptBeforeCompletion() throws Exception {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubQuery(jdbc, emptyRow(PAYLOAD_DIGEST));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcCandidateCommitReceiptStore store = new JdbcCandidateCommitReceiptStore(jdbc);

        CandidateCommitReceiptStore.Claim claim = store.claim(USER, KEY_DIGEST, PAYLOAD_DIGEST);
        store.complete(USER, KEY_DIGEST, PAYLOAD_DIGEST, PLAN, 2, VERSION);

        assertThat(claim.replay()).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(sql.capture(), parameters.capture());
        assertThat(sql.getAllValues().get(1))
                .contains("SET plan_id = ?, version_no = ?, version_id = ?");
        Object[] completion = parameters.getAllValues().get(1);
        assertThat((byte[]) completion[0]).containsExactly(bytes(PLAN));
        assertThat(completion[1]).isEqualTo(2);
        assertThat((byte[]) completion[2]).containsExactly(bytes(VERSION));
    }

    @Test
    void replaysACompletedReceiptAndRejectsAStoredPayloadDigestMismatch() throws Exception {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubQuery(jdbc, completedRow(PAYLOAD_DIGEST), completedRow("33".repeat(32)));
        JdbcCandidateCommitReceiptStore store = new JdbcCandidateCommitReceiptStore(jdbc);

        assertThat(store.find(USER, KEY_DIGEST, PAYLOAD_DIGEST))
                .contains(new CandidateCommitReceiptStore.Receipt(PLAN, 2, VERSION));
        assertThatThrownBy(() -> store.find(USER, KEY_DIGEST, PAYLOAD_DIGEST))
                .isInstanceOf(CandidateCommitService.IdempotencyKeyReusedException.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubQuery(JdbcOperations jdbc, ResultSet... rows) {
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rows[index.getAndIncrement()], 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private static ResultSet emptyRow(String payloadDigest) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getBytes("payload_digest")).thenReturn(HexFormat.of().parseHex(payloadDigest));
        return row;
    }

    private static ResultSet completedRow(String payloadDigest) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getBytes("payload_digest")).thenReturn(HexFormat.of().parseHex(payloadDigest));
        when(row.getBytes("plan_id")).thenReturn(bytes(PLAN));
        when(row.getObject("version_no", Integer.class)).thenReturn(2);
        when(row.getBytes("version_id")).thenReturn(bytes(VERSION));
        return row;
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
