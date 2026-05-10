package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.IdempotencyStore.ClaimResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IdempotencyStore.ClaimResult")
class IdempotencyStoreTest {

    @Test
    void claimed_factory() {
        var result = ClaimResult.claimed("abc123");
        assertThat(result.status()).isEqualTo(ClaimResult.Status.CLAIMED);
        assertThat(result.idempotencyKey()).isEqualTo("abc123");
        assertThat(result.isClaimed()).isTrue();
    }

    @Test
    void duplicate_factory() {
        var result = ClaimResult.duplicate("abc123");
        assertThat(result.status()).isEqualTo(ClaimResult.Status.DUPLICATE);
        assertThat(result.idempotencyKey()).isEqualTo("abc123");
        assertThat(result.isClaimed()).isFalse();
    }

    @Test
    void rejects_null_status() {
        assertThatThrownBy(() -> new ClaimResult(null, "key"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_key() {
        assertThatThrownBy(() -> new ClaimResult(ClaimResult.Status.CLAIMED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void all_statuses_exist() {
        assertThat(ClaimResult.Status.values()).containsExactly(
                ClaimResult.Status.CLAIMED,
                ClaimResult.Status.DUPLICATE);
    }
}
