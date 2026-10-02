package edu.cit.jabines.supplier.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP/XML transport for the LegacySupply API. Internal to the supplier module.
 *
 * Every failure is reported as a {@link LegacySupplyException} (or its subclass
 * {@link SessionExpiredException}) carrying the supplier's error code and a
 * "retryable" flag, so the adapter can decide what to do without parsing anything.
 * Neither the API key nor the session token is ever logged.
 */
@Slf4j
@Component
public class LegacySupplyClient {

    /** Hard cap required by the lab: no LegacySupply call may wait longer than 3 seconds. */
    private static final int MAX_TIMEOUT_SECONDS = 3;

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
        // The manual's replies carry fields we do not use (IssuedAt, CreatedAt, ...).
        this.xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        Timeout timeout = Timeout.ofSeconds(Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS));

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .build();

        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    /** POST /auth/token. Returns the session token. */
    public String signIn() throws IOException {
        log.info("Signing in to LegacySupply");

        HttpPost request = new HttpPost(baseUrl + "/auth/token");
        request.setEntity(new StringEntity(
                xmlMapper.writeValueAsString(new AuthRequest(clientId, apiKey)),
                ContentType.APPLICATION_XML));
        request.setHeader("Content-Type", "application/xml");

        String body;
        try {
            body = send(request);
        } catch (SessionExpiredException e) {
            // A 401 while signing in means our credentials were refused, not an expired session.
            throw new LegacySupplyException(e.getErrorCode(), e.getHttpStatus(),
                    "Sign-in rejected: " + e.getMessage(), false);
        }

        AuthResponse response = parse(body, AuthResponse.class);
        if (response.getSessionToken() == null || response.getSessionToken().isBlank()) {
            throw new LegacySupplyException("LOCAL-PARSE", 200, "Sign-in reply had no session token", false);
        }
        log.info("Sign in successful");
        return response.getSessionToken();
    }

    /** GET /catalog. */
    public List<CatalogItem> getCatalog(String sessionToken) throws IOException {
        log.info("Reading LegacySupply catalog");

        HttpGet request = new HttpGet(baseUrl + "/catalog");
        request.setHeader("X-LS-Session", sessionToken);

        CatalogResponse response = parse(send(request), CatalogResponse.class);
        List<CatalogItem> items = response.items == null ? new ArrayList<>() : response.items;
        log.info("Catalog read: {} items", items.size());
        return items;
    }

    /**
     * POST /purchase-orders. Always sends X-Request-Id.
     * The manual defines no Uom in the request (Qty is in LegacySupply's own unit, cases),
     * so the {@code uom} argument is accepted for compatibility but not sent.
     */
    public PurchaseOrderResponse submitPurchaseOrder(
            String sessionToken,
            String requestId,
            String buyerRef,
            String supplierSku,
            int quantity,
            String uom) throws IOException {

        log.info("Submitting purchase order: buyerRef={}, sku={}, qty={}, requestId={}",
                buyerRef, supplierSku, quantity, requestId);

        HttpPost request = new HttpPost(baseUrl + "/purchase-orders");
        request.setEntity(new StringEntity(
                xmlMapper.writeValueAsString(new PurchaseOrderRequest(supplierSku, quantity, buyerRef)),
                ContentType.APPLICATION_XML));
        request.setHeader("Content-Type", "application/xml");
        request.setHeader("X-LS-Session", sessionToken);
        request.setHeader("X-Request-Id", requestId);

        PurchaseOrderResponse response = parse(send(request), PurchaseOrderResponse.class);
        log.info("Purchase order accepted by supplier: po={}", response.getPoNumber());
        return response;
    }

    /** GET /purchase-orders/{PoNumber}. */
    public StatusResponse checkOrderStatus(String sessionToken, String poNumber) throws IOException {
        log.debug("Checking PO status: {}", poNumber);

        HttpGet request = new HttpGet(baseUrl + "/purchase-orders/"
                + URLEncoder.encode(poNumber, StandardCharsets.UTF_8));
        request.setHeader("X-LS-Session", sessionToken);

        StatusResponse response = parse(send(request), StatusResponse.class);
        log.info("PO status: po={}, statusCode={}", poNumber, response.getStatus());
        return response;
    }

    /**
     * GET /purchase-orders?buyerRef={BuyerRef}. Used to find out whether an order we are
     * unsure about already exists. The manual does not name the per-order element inside
     * PurchaseOrderList, so orders are located by their PoNumber field instead of by name.
     */
    public List<StatusResponse> findOrdersByBuyerRef(String sessionToken, String buyerRef) throws IOException {
        log.debug("Looking up orders by BuyerRef: {}", buyerRef);

        HttpGet request = new HttpGet(baseUrl + "/purchase-orders?buyerRef="
                + URLEncoder.encode(buyerRef, StandardCharsets.UTF_8));
        request.setHeader("X-LS-Session", sessionToken);

        String body = send(request);
        List<StatusResponse> orders = new ArrayList<>();
        try {
            collectOrders(xmlMapper.readTree(body), orders);
        } catch (JsonProcessingException e) {
            throw new LegacySupplyException("LOCAL-PARSE", 200, "Unreadable order list reply", false);
        }
        return orders;
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** Sends the request; returns the body of a 2xx reply, otherwise throws a classified exception. */
    private String send(ClassicHttpRequest request) throws IOException {
        HttpClientResponseHandler<String> handler = response -> {
            int status = response.getCode();
            String body = readBody(response);
            if (status >= 200 && status < 300) {
                return body;
            }
            throw toException(status, body);
        };

        try {
            return httpClient.execute(request, handler);
        } catch (LegacySupplyException e) {
            throw e; // already classified (includes SessionExpiredException)
        } catch (InterruptedIOException e) {
            log.warn("LegacySupply call timed out");
            throw new LegacySupplyException("TIMEOUT", 0, "Request timed out", true);
        } catch (IOException e) {
            log.warn("LegacySupply connection problem: {}", e.getClass().getSimpleName());
            throw new LegacySupplyException("CONNECTION", 0,
                    "Could not reach LegacySupply: " + e.getClass().getSimpleName(), true);
        }
    }

    private static String readBody(ClassicHttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return "";
        }
        try (InputStream in = entity.getContent()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private LegacySupplyException toException(int status, String body) {
        String code = null;
        String message = null;
        try {
            LsError error = xmlMapper.readValue(body, LsError.class);
            code = error.code;
            message = error.message;
        } catch (Exception ignored) {
            // body was not an LSError document
        }
        if (code == null) {
            code = "HTTP-" + status;
        }
        if (message == null) {
            message = "HTTP " + status;
        }
        log.warn("LegacySupply error: http={} code={} message={}", status, code, message);

        // Credentials rejected / header missing: signing in again will not help.
        if ("E-AUTH-01".equals(code) || "E-AUTH-02".equals(code)) {
            return new LegacySupplyException(code, status, message, false);
        }
        // Session not recognised / not valid (or any other 401): sign in again.
        if ("E-AUTH-03".equals(code) || "E-AUTH-07".equals(code) || status == 401) {
            return new SessionExpiredException(code, status, message);
        }
        // Rate limit and server-side trouble are temporary; everything else is permanent.
        boolean retryable = status == 429 || status >= 500;
        return new LegacySupplyException(code, status, message, retryable);
    }

    private <T> T parse(String body, Class<T> type) throws LegacySupplyException {
        try {
            return xmlMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new LegacySupplyException("LOCAL-PARSE", 200,
                    "Could not read " + type.getSimpleName() + " reply", false);
        }
    }

    private void collectOrders(JsonNode node, List<StatusResponse> out) throws JsonProcessingException {
        if (node == null) {
            return;
        }
        if (node.isObject() && node.has("PoNumber")) {
            out.add(xmlMapper.treeToValue(node, StatusResponse.class));
            return;
        }
        for (JsonNode child : node) {
            collectOrders(child, out);
        }
    }

    // ------------------------------------------------------------------
    // Exceptions
    // ------------------------------------------------------------------

    /** A classified LegacySupply failure. */
    public static class LegacySupplyException extends IOException {
        private final String errorCode;
        private final int httpStatus;
        private final boolean retryable;

        public LegacySupplyException(String errorCode, int httpStatus, String message, boolean retryable) {
            super(message);
            this.errorCode = errorCode;
            this.httpStatus = httpStatus;
            this.retryable = retryable;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        /** True only for failures that may succeed if tried again (rate limit, 5xx, timeout, connection). */
        public boolean isRetryable() {
            return retryable;
        }
    }

    /** The session was not accepted (E-AUTH-03 / E-AUTH-07); sign in again, then retry. */
    public static class SessionExpiredException extends LegacySupplyException {
        public SessionExpiredException(String errorCode, int httpStatus, String message) {
            super(errorCode, httpStatus, message, false);
        }
    }

    // ------------------------------------------------------------------
    // XML documents
    // ------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LsError {
        @JacksonXmlProperty(localName = "Code")
        public String code;

        @JacksonXmlProperty(localName = "Message")
        public String message;
    }

    @JacksonXmlRootElement(localName = "AuthRequest")
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthResponse {

        @JacksonXmlProperty(localName = "SessionToken")
        public String sessionToken;

        public String getSessionToken() {
            return sessionToken;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CatalogResponse {

        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "Item")
        public List<CatalogItem> items;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CatalogItem {

        @JacksonXmlProperty(localName = "SupplierSku")
        public String supplierSku;

        @JacksonXmlProperty(localName = "Description")
        public String description;

        @JacksonXmlProperty(localName = "PackSize")
        public int packSize;

        public String getSupplierSku() {
            return supplierSku;
        }

        public String getDescription() {
            return description;
        }

        public int getPackSize() {
            return packSize;
        }
    }

    @JacksonXmlRootElement(localName = "PurchaseOrder")
    public static class PurchaseOrderRequest {

        @JacksonXmlProperty(localName = "SupplierSku")
        public String supplierSku;

        @JacksonXmlProperty(localName = "Qty")
        public int quantity;

        @JacksonXmlProperty(localName = "BuyerRef")
        public String buyerRef;

        public PurchaseOrderRequest() {
        }

        public PurchaseOrderRequest(String supplierSku, int quantity, String buyerRef) {
            this.supplierSku = supplierSku;
            this.quantity = quantity;
            this.buyerRef = buyerRef;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

        @JacksonXmlProperty(localName = "CreatedAt")
        public String createdAt;

        public String getPoNumber() {
            return poNumber;
        }

        public String getStatus() {
            return status;
        }

        public String getUom() {
            return uom;
        }

        public int getQuantity() {
            return quantity;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

        @JacksonXmlProperty(localName = "CreatedAt")
        public String createdAt;

        @JacksonXmlProperty(localName = "CheckedAt")
        public String updatedAt;

        public String getPoNumber() {
            return poNumber;
        }

        public String getStatus() {
            return status;
        }

        public String getBuyerRef() {
            return buyerRef;
        }

        public String getUom() {
            return uom;
        }

        public int getQuantity() {
            return quantity;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }
    }
}
