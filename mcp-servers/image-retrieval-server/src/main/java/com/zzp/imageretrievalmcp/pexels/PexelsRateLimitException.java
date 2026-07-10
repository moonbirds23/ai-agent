package com.zzp.imageretrievalmcp.pexels;

/**
 * Thrown when Pexels API returns HTTP 429 (rate limit exceeded).
 */
public class PexelsRateLimitException extends RuntimeException {

    public PexelsRateLimitException(String message) {
        super(message);
    }
}
