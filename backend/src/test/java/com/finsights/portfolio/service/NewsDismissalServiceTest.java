package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.DismissedNewsItem;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.DismissedNewsItemRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NewsDismissalServiceTest {

    @Mock DismissedNewsItemRepository repo;
    @Mock CurrentUserService currentUserService;

    private NewsDismissalService service;
    private UserAccount user;

    @BeforeEach
    void setUp() throws Exception {
        service = new NewsDismissalService(repo, currentUserService);
        user = new UserAccount("demo@finsights.local", "Demo");
        setField(user, "id", "u-1");
    }

    private static void setField(Object entity, String name, Object value) throws Exception {
        Field field = entity.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(entity, value);
    }

    @Test
    void dismissSavesANewRowForAnArticleNotAlreadyDismissed() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(repo.existsByUser_IdAndLink("u-1", "https://a/article")).thenReturn(false);

        service.dismiss("https://a/article");

        ArgumentCaptor<DismissedNewsItem> captor = ArgumentCaptor.forClass(DismissedNewsItem.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getLink()).isEqualTo("https://a/article");
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void dismissIsIdempotentForAnAlreadyDismissedArticle() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(repo.existsByUser_IdAndLink("u-1", "https://a/article")).thenReturn(true);

        service.dismiss("https://a/article");

        verify(repo, never()).save(any());
    }

    @Test
    void dismissTrimsWhitespaceAroundTheLink() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(repo.existsByUser_IdAndLink("u-1", "https://a/article")).thenReturn(false);

        service.dismiss("  https://a/article  ");

        ArgumentCaptor<DismissedNewsItem> captor = ArgumentCaptor.forClass(DismissedNewsItem.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getLink()).isEqualTo("https://a/article");
    }

    @Test
    void dismissDoesNothingForABlankLink() {
        service.dismiss("   ");
        service.dismiss(null);

        verify(repo, never()).existsByUser_IdAndLink(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void dismissedLinksReturnsEveryLinkTheCurrentUserHasDismissed() {
        when(currentUserService.currentUser()).thenReturn(user);
        DismissedNewsItem a = row("https://a");
        DismissedNewsItem b = row("https://b");
        when(repo.findByUser_Id("u-1")).thenReturn(List.of(a, b));

        assertThat(service.dismissedLinks()).isEqualTo(Set.of("https://a", "https://b"));
    }

    @Test
    void pruneDelegatesToTheRepositoryWithTheGivenCutoff() {
        Instant cutoff = Instant.parse("2026-10-01T00:00:00Z");

        service.prune(cutoff);

        verify(repo).deleteOlderThan(cutoff);
    }

    private DismissedNewsItem row(String link) {
        DismissedNewsItem d = new DismissedNewsItem();
        d.setUser(user);
        d.setLink(link);
        return d;
    }
}
