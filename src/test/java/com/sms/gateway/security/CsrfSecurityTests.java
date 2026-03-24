package com.sms.gateway.security;

import com.sms.gateway.adminuser.AdminUserLoginOtpService;
import com.sms.gateway.adminuser.AdminUserRepository;
import com.sms.gateway.adminuser.AdminUserService;
import com.sms.gateway.api.AuthController;
import com.sms.gateway.api.CsrfController;
import com.sms.gateway.api.SmsController;
import com.sms.gateway.service.SmsService;
import com.sms.gateway.users.ApiClient;
import com.sms.gateway.users.ApiClientService;
import com.sms.gateway.users.ApiUsageLogService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {CsrfController.class, AuthController.class, SmsController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.security.cors.allowed-origins=http://localhost:3000",
        "app.security.cors.allowed-headers=Authorization,Content-Type,X-XSRF-TOKEN,X-HTTP-Method-Override",
        "app.security.cors.allow-credentials=true"
})
class CsrfSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiClientService apiClientService;

    @MockBean
    private JwtTokenService jwtTokenService;

    @MockBean
    private AdminUserRepository adminUserRepository;

    @MockBean
    private AdminUserService adminUserService;

    @MockBean
    private AdminUserLoginOtpService adminUserLoginOtpService;

    @MockBean
    private SmsService smsService;

    @MockBean
    private ApiUsageLogService apiUsageLogService;

    @Test
    void csrfBootstrapEndpointSetsReadableCookieAndCorsHeaders() throws Exception {
        MvcResult result = mockMvc.perform(get("/csrf")
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andReturn();

        Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isFalse();
        assertThat(csrfCookie.getValue()).isNotBlank();
    }

    @Test
    void loginRejectsMissingCsrfToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"secret"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"admin","password":"secret"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void loginAcceptsMatchingCookieAndHeaderCsrfToken() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/csrf")).andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"secret"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"admin","password":"secret"}
                    """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void smsApiPostRemainsCsrfFreeForApiClients() throws Exception {
        ApiClient apiClient = new ApiClient();
        apiClient.setUsername("client-one");
        apiClient.setPasswordHash("irrelevant");
        apiClient.setDescription("Integration client");

        given(apiClientService.authenticate("client-one", "secret")).willReturn(apiClient);
        given(smsService.enqueue(anyString(), anyString(), anyString(), anyString(), any(), any())).willReturn("req-123");

        mockMvc.perform(post("/api/sms")
                        .header("X-Api-Username", "client-one")
                        .header("X-Api-Password", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "to":"256700000000",
                                  "text":"hello",
                                  "senderId":"ACME",
                                  "idempotencyKey":"idem-1"
                                }
                                """))
                .andExpect(status().isAccepted());
    }
}