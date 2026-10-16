package com.jordanbikong.ledger.account;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.jordanbikong.ledger.TestcontainersConfiguration;

/** Sends real HTTP requests through the full app (controller → service → Postgres). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void createReturns201WithLocationAndZeroBalance() throws Exception {
        String name = uniqueName("Cash");

        createAccount(name, "ASSET", "CAD")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/accounts/\\d+")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.type").value("ASSET"))
                .andExpect(jsonPath("$.currency").value("CAD"))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.allowNegative").value(false))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void createAcceptsAllowNegative() throws Exception {
        String body = """
                {"name": "%s", "type": "ASSET", "currency": "CAD", "allowNegative": true}
                """.formatted(uniqueName("Credit line"));

        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowNegative").value(true));
    }

    @Test
    void getReturnsTheCreatedAccount() throws Exception {
        String name = uniqueName("Sales");
        long id = idOf(createAccount(name, "REVENUE", "CAD"));

        mvc.perform(get("/accounts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.type").value("REVENUE"));
    }

    @Test
    void getMissingAccountReturns404Problem() throws Exception {
        mvc.perform(get("/accounts/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Account " + Long.MAX_VALUE + " does not exist"));
    }

    @Test
    void getWithNonNumericIdReturns400() throws Exception {
        mvc.perform(get("/accounts/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void invalidFieldsReturn400WithFieldErrors() throws Exception {
        createAccount("  ", "ASSET", "cad")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "currency")));
    }

    @Test
    void unknownTypeReturns400() throws Exception {
        createAccount(uniqueName("Mystery"), "CRYPTO", "CAD")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void duplicateNameReturns409() throws Exception {
        String name = uniqueName("Bank");
        createAccount(name, "ASSET", "CAD").andExpect(status().isCreated());

        createAccount(name, "ASSET", "USD")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An account named '" + name + "' already exists"));
    }

    @Test
    void listIncludesCreatedAccounts() throws Exception {
        String name = uniqueName("Rent");
        createAccount(name, "EXPENSE", "CAD").andExpect(status().isCreated());

        mvc.perform(get("/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem(name)));
    }

    // --- helpers -----------------------------------------------------------

    private ResultActions createAccount(String name, String type, String currency) throws Exception {
        String body = """
                {"name": "%s", "type": "%s", "currency": "%s"}
                """.formatted(name, type, currency);
        return mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long idOf(ResultActions result) throws Exception {
        Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
