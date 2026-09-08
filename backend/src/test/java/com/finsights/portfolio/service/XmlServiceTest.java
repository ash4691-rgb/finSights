package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.dto.ImportResultResponse;
import com.finsights.portfolio.dto.TransactionRequest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class XmlServiceTest {

    @Mock TransactionService transactionService;

    @Test
    void importsTransactionAndCreatesWhenIdMissing() {
        XmlService xml = new XmlService(transactionService);
        String body = """
                <transactions>
                  <transaction>
                    <holdingId>h-1</holdingId>
                    <type>BUY</type>
                    <date>2026-01-15</date>
                    <amount>50000</amount>
                    <quantity>10</quantity>
                    <notes>Initial buy</notes>
                  </transaction>
                </transactions>
                """;

        ImportResultResponse result = xml.importXml(body);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).create(captor.capture());
        assertThat(captor.getValue().holdingId()).isEqualTo("h-1");
        assertThat(captor.getValue().date()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void updatesExistingTransactionWhenIdPresent() {
        XmlService xml = new XmlService(transactionService);
        String body = """
                <transactions>
                  <transaction>
                    <id>t-1</id>
                    <holdingId>h-2</holdingId>
                    <type>SELL</type>
                    <date>2026-03-01</date>
                    <amount>20000</amount>
                  </transaction>
                </transactions>
                """;

        xml.importXml(body);

        verify(transactionService).update(eq("t-1"), any());
    }

    @Test
    void rejectsDoctypeDeclarationsToPreventXxe() {
        XmlService xml = new XmlService(transactionService);
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE transactions [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <transactions><transaction><holdingId>&xxe;</holdingId></transaction></transactions>
                """;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> xml.importXml(malicious))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exportProducesParsableXml() {
        XmlService xml = new XmlService(transactionService);
        when(transactionService.list(null, null, null, null, null, null)).thenReturn(java.util.List.of());
        String exported = xml.export();
        assertThat(exported).contains("<transactions");
    }
}
