package com.bmp.pricing.application.query;

import com.bmp.web.pagination.PageResponse;

/**
 * A page of a list's prices and the list's price version read before them, so the version (the ETag clients send as
 * {@code If-Match}) is never newer than the prices shown: a concurrent change makes the next upsert fail with 412
 * instead of overwriting a change the client never saw.
 *
 * @param version the list's price version when the page was read
 * @param page the prices
 */
public record PricePageView(int version, PageResponse<PriceView> page) {}
