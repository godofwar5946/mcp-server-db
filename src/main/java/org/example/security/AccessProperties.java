package org.example.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.util.*;

@Validated
@ConfigurationProperties(prefix = "app.security")
public class AccessProperties {
    @NotNull @Valid
    private Map<String, Client> clients = new LinkedHashMap<>();
    @NotNull
    private String approvalKey = "";

    public Map<String, Client> getClients() { return clients; }
    public void setClients(Map<String, Client> value) { this.clients = value; }

    public String getApprovalKey() { return approvalKey; }
    public void setApprovalKey(String value) { this.approvalKey = value; }

    @AssertTrue(message = "审批密钥必须至少 32 字符")
    public boolean isApprovalKeyValid() {
        return approvalKey == null || approvalKey.isBlank() || approvalKey.length() >= 32;
    }

    public static class Client {
        private String token = "";
        public String getToken() { return token; }
        public void setToken(String value) { this.token = value; }
    }
}
