package edu.cit.jabines.supplier.internal;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Low-level HTTP client for LegacySupply API.
 * Handles XML serialization, authentication, timeouts, and retries.
 * 
 * All LegacySupply-specific details are internal to this class.
 */
@Slf4j
@Component
public class LegacySupplyClient {

    private final String baseUrl;
    private final String apiKey;
    private final String clientId;
    private final int timeoutSeconds;
    private final HttpClient httpClient;
    private final XmlMapper xmlMapper;

    public LegacySupplyClient(
            @Value("${legacysupply.base-url}") String baseUrl,
            @Value("${legacysupply.api-key}") String apiKey,
            @Value("${legacysupply.client-id}") String clientId,
            @Value("${legacysupply.timeout-seconds}") int timeoutSeconds) {

        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.clientId = clientId;
        this.timeoutSeconds = timeoutSeconds;
        this.xmlMapper = new XmlMapper();

        // Configure HTTP client with timeouts
        Timeout timeout = Timeout.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClients.custom()
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build()
                )
                .build();
    }

    /**
     * Sign in to LegacySupply and get session token
     */
    public String signIn() throws IOException {
        log.info("Signing in to LegacySupply");

        String endpoint = baseUrl + "/signin";
        HttpPost request = new HttpPost(endpoint);

        // Build sign-in request XML
        SignInRequest signInRequest = new SignInRequest(clientId, apiKey);
        String xmlBody = xmlMapper.writeValueAsString(signInRequest);

        request.setEntity(new StringEntity(xmlBody, ContentType.APPLICATION_XML));
        request.setHeader("Content-Type", "application/xml");

        try {
            String response = httpClient.execute(request, httpResponse -> {
                int statusCode = httpResponse.getCode();
                String body = new String(httpResponse.getEntity().getContent().readAllBytes());

                if (statusCode == 200) {
                    try {
                        SignInResponse signinResp = xmlMapper.readValue(body, SignInResponse.class);
                        log.info("Sign in successful, session token received");
                        return signinResp.getSessionToken();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse sign-in response: " + body, e);
                    }
                } else {
                    throw new RuntimeException("Sign-in failed with status " + statusCode + ": " + body);
                }
            });

            return response;
        } catch (Exception e) {
            log.error("Sign-in failed", e);
            throw new IOException("Sign-in failed: " + e.getMessage(), e);
        }
    }

    /**
     * Submit a purchase order to LegacySupply
     */
    public PurchaseOrderResponse submitPurchaseOrder(
            String sessionToken,
            String requestId,
            String buyerRef,
            String supplierSku,
            int quantity,
            String uom) throws IOException {

        log.info("Submitting purchase order: buyerRef={}, sku={}, qty={}", buyerRef, supplierSku, quantity);

        String endpoint = baseUrl + "/purchase-orders";
        HttpPost request = new HttpPost(endpoint);

        // Build purchase order request XML
        PurchaseOrderRequest poRequest = new PurchaseOrderRequest(
                requestId, buyerRef, supplierSku, quantity, uom
        );
        String xmlBody = xmlMapper.writeValueAsString(poRequest);

        request.setEntity(new StringEntity(xmlBody, ContentType.APPLICATION_XML));
        request.setHeader("Content-Type", "application/xml");
        request.setHeader("Authorization", "Bearer " + sessionToken);
        request.setHeader("X-Request-Id", requestId);

        try {
            PurchaseOrderResponse response = httpClient.execute(request, httpResponse -> {
                int statusCode = httpResponse.getCode();
                String body = new String(httpResponse.getEntity().getContent().readAllBytes());

                if (statusCode == 200 || statusCode == 201) {
                    try {
                        PurchaseOrderResponse poResp = xmlMapper.readValue(body, PurchaseOrderResponse.class);
                        log.info("Purchase order submitted successfully, PO#: {}", poResp.getPoNumber());
                        return poResp;
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse PO response: " + body, e);
                    }
                } else if (statusCode == 401) {
                    throw new SessionExpiredException("Session expired (401)");
                } else if (statusCode == 400) {
                    throw new RuntimeException("Invalid purchase order request (400): " + body);
                } else {
                    throw new RuntimeException("PO submission failed with status " + statusCode + ": " + body);
                }
            });

            return response;
        } catch (SessionExpiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("Purchase order submission failed", e);
            throw new IOException("PO submission failed: " + e.getMessage(), e);
        }
    }

    /**
     * Check the status of a purchase order
     */
    public StatusResponse checkOrderStatus(String sessionToken, String poNumber) throws IOException {
        log.debug("Checking PO status: {}", poNumber);

        String endpoint = baseUrl + "/status?po=" + poNumber;
        HttpPost request = new HttpPost(endpoint);

        request.setHeader("Authorization", "Bearer " + sessionToken);

        try {
            StatusResponse response = httpClient.execute(request, httpResponse -> {
                int statusCode = httpResponse.getCode();
                String body = new String(httpResponse.getEntity().getContent().readAllBytes());

                if (statusCode == 200) {
                    try {
                        StatusResponse statusResp = xmlMapper.readValue(body, StatusResponse.class);
                        log.debug("Status check result: po={}, status={}", poNumber, statusResp.getStatus());
                        return statusResp;
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse status response: " + body, e);
                    }
                } else if (statusCode == 401) {
                    throw new SessionExpiredException("Session expired (401)");
                } else {
                    throw new RuntimeException("Status check failed with status " + statusCode + ": " + body);
                }
            });

            return response;
        } catch (SessionExpiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("Status check failed", e);
            throw new IOException("Status check failed: " + e.getMessage(), e);
        }
    }

    /**
     * Exception thrown when session is expired or invalid
     */
    public static class SessionExpiredException extends IOException {
        public SessionExpiredException(String message) {
            super(message);
        }
    }

    // ============= XML DTO Classes =============

    public static class SignInRequest {
        public String clientId;
        public String apiKey;

        public SignInRequest() {}
        public SignInRequest(String clientId, String apiKey) {
            this.clientId = clientId;
            this.apiKey = apiKey;
        }
    }

    public static class SignInResponse {
        public String sessionToken;

        public String getSessionToken() {
            return sessionToken;
        }
    }

    public static class PurchaseOrderRequest {
        public String requestId;
        public String buyerRef;
        public String supplierSku;
        public int quantity;
        public String uom;

        public PurchaseOrderRequest() {}
        public PurchaseOrderRequest(String requestId, String buyerRef, String supplierSku, int quantity, String uom) {
            this.requestId = requestId;
            this.buyerRef = buyerRef;
            this.supplierSku = supplierSku;
            this.quantity = quantity;
            this.uom = uom;
        }
    }

    public static class PurchaseOrderResponse {
        public String poNumber;
        public String status;

        public String getPoNumber() {
            return poNumber;
        }

        public String getStatus() {
            return status;
        }
    }

    public static class StatusResponse {
        public String poNumber;
        public String status;
        public String updatedAt;

        public String getPoNumber() {
            return poNumber;
        }

        public String getStatus() {
            return status;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }
    }

}
