package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GokuUploadStoreTest {

    private final GokuUploadStore store = new GokuUploadStore();

    @Test
    void theUploadingUserCanReadItBack() {
        String id = store.store("user-a", "statement.pdf", "bytes".getBytes());

        assertThat(store.take("user-a", id)).isEqualTo("bytes".getBytes());
    }

    @Test
    void aDifferentUserGetsNotFoundEvenWithTheRealId() {
        String id = store.store("user-a", "statement.pdf", "bytes".getBytes());

        assertThatThrownBy(() -> store.take("user-b", id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aWrongUsersFailedAttemptDoesNotConsumeTheRealOwnersUpload() {
        String id = store.store("user-a", "statement.pdf", "bytes".getBytes());

        assertThatThrownBy(() -> store.take("user-b", id)).isInstanceOf(ResponseStatusException.class);

        assertThat(store.take("user-a", id)).isEqualTo("bytes".getBytes());
    }

    @Test
    void isConsumedOnFirstRead() {
        String id = store.store("user-a", "statement.pdf", "bytes".getBytes());

        store.take("user-a", id);

        assertThatThrownBy(() -> store.take("user-a", id)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anUnknownIdIsNotFound() {
        assertThatThrownBy(() -> store.take("user-a", "no-such-id")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aNullIdIsNotFound() {
        assertThatThrownBy(() -> store.take("user-a", null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsAnEmptyUpload() {
        assertThatThrownBy(() -> store.store("user-a", "empty.pdf", new byte[0]))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsAnOversizedUpload() {
        assertThatThrownBy(() -> store.store("user-a", "huge.pdf", new byte[16 * 1024 * 1024]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MB");
    }
}
