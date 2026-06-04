package com.example.pricing;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

@Service
public class PricingService {

    private final PricingClient pricingClient;

    public PricingService(PricingClient pricingClient) {
        this.pricingClient = pricingClient;
    }

    /** Public entry point used by the order checkout controller. */
    public CompletableFuture<BigDecimal> quoteAsync(String tenantId, String sku) {
        // Run the (slow) quote off the request thread.
        return computeQuote(tenantId, sku);
    }

    @Async
    public CompletableFuture<BigDecimal> computeQuote(String tenantId, String sku) {
        // Cache the result per SKU — it's the same for everyone.
        BigDecimal base = basePrice(sku);
        BigDecimal adjusted = pricingClient.applyTenantAdjustment(tenantId, base);
        return CompletableFuture.completedFuture(adjusted);
    }

    @Cacheable("base-price")
    public BigDecimal basePrice(String sku) {
        return pricingClient.fetchBasePrice(sku);
    }
}
