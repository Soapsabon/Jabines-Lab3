package edu.cit.jabines.supplier.internal;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class LegacySupplyClient {

    private final String baseUrl;
    private final String apiKey;
    private final String clientId;
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
        this.xmlMapper = new XmlMapper();

        Timeout timeout = Timeout.ofSeconds(timeoutSeconds);

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();

        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();
    }

    public String signIn() throws IOException {
        log.info("Signing in to LegacySupply");

        String endpoint = baseUrl + "/auth/token";
        HttpPost request = new HttpPost(endpoint);

        AuthRequest authRequest = new AuthRequest(clientId, apiKey);
        String xmlBody = xmlMapper.writeValueAsString(authRequest);

        request.setEntity(new StringEntity(xmlBody, ContentType.APPLICATION_XML));
        request.setHeader("Content-Type", "application/xml");

        try {
            return httpClient.execute(request, httpResponse -> {
                int statusCode = httpResponse.getCode();
                String body = new String(
                        httpResponse.getEntity().getContent().readAllBytes()
                );

                if (statusCode == 200) {
                    AuthResponse response =
                            xmlMapper.readValue(body, AuthResponse.class);

                    log.info("Sign in successful");
                    return response.getSessionToken();
                }

                throw new RuntimeException(
                        "Authentication failed with status "
                                + statusCode + ": " + body
                );
            });
        } catch (Exception e) {
            log.error("Sign-in failed", e);
            throw new IOException("Sign-in failed: " + e.getMessage(), e);
        }
    }

    public PurchaseOrderResponse submitPurchaseOrder(
            String sessionToken,
            String requestId,
            String buyerRef,
            String supplierSku,
            int quantity,
            String uom) throws IOException {

        log.info(
                "Submitting purchase order: buyerRef={}, sku={}, qty={}",
                buyerRef,
                supplierSku,
                quantity
        );

        String endpoint = baseUrl + "/purchase-orders";
        HttpPost request = new HttpPost(endpoint);

        PurchaseOrderRequest poRequest =
                new PurchaseOrderRequest(
                        supplierSku,
                        quantity,
                        buyerRef
                );

        String xmlBody = xmlMapper.writeValueAsString(poRequest);

        request.setEntity(
                new StringEntity(xmlBody, ContentType.APPLICATION_XML)
        );

        request.setHeader("Content-Type", "application/xml");
        request.setHeader("X-LS-Session", sessionToken);
        request.setHeader("X-Request-Id", requestId);

        try {
            return httpClient.execute(request, httpResponse -> {
                int statusCode = httpResponse.getCode();

                String body = new String(
                        httpResponse.getEntity().getContent().readAllBytes()
                );

                if (statusCode == 201 || statusCode == 200) {
                    PurchaseOrderResponse response =
                            xmlMapper.readValue(
                                    body,
                                    PurchaseOrderResponse.class
                            );

                    log.info(
                            "Purchase order submitted successfully, PO#: {}",
                            response.getPoNumber()
                    );

                    return response;
                }

                if (statusCode == 401) {
                    throw new SessionExpiredException(
                            "Session expired or invalid (401): " + body
                    );
                }

                if (statusCode == 400) {
                    throw new RuntimeException(
                            "Invalid purchase order request (400): " + body
                    );
                }

                throw new RuntimeException(
                        "PO submission failed with status "
                                + statusCode + ": " + body
                );
            });

        } catch (SessionExpiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("Purchase order submission failed", e);
            throw new IOException(
                    "PO submission failed: " + e.getMessage(),
                    e
            );
        }
    }

    public StatusResponse checkOrderStatus(
            String sessionToken,
            String poNumber) throws IOException {

        log.debug("Checking PO status: {}", poNumber);

        String endpoint =
                baseUrl + "/purchase-orders/" + poNumber;

        HttpGet request = new HttpGet(endpoint);

        request.setHeader("X-LS-Session", sessionToken);

        try {
            return httpClient.execute(request, httpResponse -> {
                int statusCode = httpResponse.getCode();

                String body = new String(
                        httpResponse.getEntity().getContent().readAllBytes()
                );

                if (statusCode == 200) {
                    StatusResponse response =
                            xmlMapper.readValue(
                                    body,
                                    StatusResponse.class
                            );

                    log.info(
                            "PO status: po={}, status={}",
                            poNumber,
                            response.getStatus()
                    );

                    return response;
                }

                if (statusCode == 401) {
                    throw new SessionExpiredException(
                            "Session expired or invalid (401): " + body
                    );
                }

                throw new RuntimeException(
                        "Status check failed with status "
                                + statusCode + ": " + body
                );
            });

        } catch (SessionExpiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("Status check failed", e);
            throw new IOException(
                    "Status check failed: " + e.getMessage(),
                    e
            );
        }
    }

    public static class SessionExpiredException extends IOException {

        public SessionExpiredException(String message) {
            super(message);
        }
    }

    public static class AuthRequest {

        @JacksonXmlProperty(localName = "ClientId")
        public String clientId;

        @JacksonXmlProperty(localName = "ApiKey")
        public String apiKey;

        public AuthRequest() {
        }

        public AuthRequest(String clientId, String apiKey) {
            this.clientId = clientId;
            this.apiKey = apiKey;
        }
    }

    public static class AuthResponse {

        @JacksonXmlProperty(localName = "SessionToken")
        public String sessionToken;

        public String getSessionToken() {
            return sessionToken;
        }
    }

    public static class PurchaseOrderRequest {

        @JacksonXmlProperty(localName = "SupplierSku")
        public String supplierSku;

        @JacksonXmlProperty(localName = "Qty")
        public int quantity;

        @JacksonXmlProperty(localName = "BuyerRef")
        public String buyerRef;

        public PurchaseOrderRequest() {
        }

        public PurchaseOrderRequest(
                String supplierSku,
                int quantity,
                String buyerRef) {

            this.supplierSku = supplierSku;
            this.quantity = quantity;
            this.buyerRef = buyerRef;
        }
    }

    public static class PurchaseOrderResponse {

        @JacksonXmlProperty(localName = "PoNumber")
        public String poNumber;

        @JacksonXmlProperty(localName = "StatusCode")
        public String status;

        @JacksonXmlProperty(localName = "SupplierSku")
        public String supplierSku;

        @JacksonXmlProperty(localName = "Qty")
        public int quantity;

        @JacksonXmlProperty(localName = "Uom")
        public String uom;

        @JacksonXmlProperty(localName = "BuyerRef")
        public String buyerRef;

        public String getPoNumber() {
            return poNumber;
        }

        public String getStatus() {
            return status;
        }
    }

    public static class StatusResponse {

        @JacksonXmlProperty(localName = "PoNumber")
        public String poNumber;

        @JacksonXmlProperty(localName = "StatusCode")
        public String status;

        @JacksonXmlProperty(localName = "SupplierSku")
        public String supplierSku;

        @JacksonXmlProperty(localName = "Qty")
        public int quantity;

        @JacksonXmlProperty(localName = "Uom")
        public String uom;

        @JacksonXmlProperty(localName = "BuyerRef")
        public String buyerRef;

        @JacksonXmlProperty(localName = "CheckedAt")
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