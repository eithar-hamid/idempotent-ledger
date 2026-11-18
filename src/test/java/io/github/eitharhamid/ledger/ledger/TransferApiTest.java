package io.github.eitharhamid.ledger.ledger;

import io.github.eitharhamid.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract: status codes and headers a client relies on to retry safely. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void firstCallCreatesRetryReplaysReuseConflicts() throws Exception {
        String funding = createAccount("Funding", true);
        String alice = createAccount("Alice", false);
        String body = transferJson(funding, alice, 500);

        mvc.perform(post("/transfers").header("Idempotency-Key", "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));

        mvc.perform(post("/transfers").header("Idempotency-Key", "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(post("/transfers").header("Idempotency-Key", "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(transferJson(funding, alice, 999)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency key reused"));

        mvc.perform(get("/accounts/" + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(500));
    }

    @Test
    void reportsInsufficientFundsAndMissingKey() throws Exception {
        String alice = createAccount("Alice", false);
        String bob = createAccount("Bob", false);

        mvc.perform(post("/transfers").header("Idempotency-Key", "k-" + alice)
                        .contentType(MediaType.APPLICATION_JSON).content(transferJson(alice, bob, 1)))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(transferJson(alice, bob, 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void integrityEndpointReportsConsistentLedger() throws Exception {
        mvc.perform(get("/ledger/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    private String createAccount(String name, boolean allowNegative) throws Exception {
        String response = mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "currency": "AED", "allowNegative": %s}
                                """.formatted(name, allowNegative)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static String transferJson(String from, String to, long amountMinor) {
        return """
                {"fromAccountId": "%s", "toAccountId": "%s", "amountMinor": %d, "currency": "AED"}
                """.formatted(from, to, amountMinor);
    }
}
