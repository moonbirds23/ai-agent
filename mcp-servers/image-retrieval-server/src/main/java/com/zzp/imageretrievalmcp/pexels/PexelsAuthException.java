package com.zzp.imageretrievalmcp.pexels;

/**
 * Thrown when Pexels API returns HTTP 401 or 403 (authentication / authorization failure).
 */
public class PexelsAuthException extends RuntimeException {

    public PexelsAuthException(String message) {
        super(message);
    }
}
