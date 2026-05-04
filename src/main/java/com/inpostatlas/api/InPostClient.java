package com.inpostatlas.api;

import com.inpostatlas.api.dto.PointDto;
import com.inpostatlas.api.dto.PointsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

@Component
public class InPostClient {

    private static final Logger log = LoggerFactory.getLogger(InPostClient.class);

    private final RestClient restClient;
    private final AtlasApiProperties props;

    public InPostClient(AtlasApiProperties props) {
        this.props = props;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(props.readTimeoutSeconds()));
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "inpost-atlas/0.1 (technical-assignment)")
                .build();
    }

    /**
     * Fetches all points for a given country, page by page.
     * The pageConsumer is called once per non-empty page; the call is sequential
     * so callers can rely on a single thread for storage writes.
     *
     * @return total number of items handed to the consumer
     */
    public int fetchByCountry(String country, Consumer<List<PointDto>> pageConsumer) {
        int total = 0;
        int page = 1;
        while (true) {
            PointsResponse response = fetchPageWithRetry(country, page);
            List<PointDto> items = response.safeItems();
            if (items.isEmpty()) {
                break;
            }
            // Defensive client-side filter: even if the API ignores ?country=, we only keep PL.
            List<PointDto> filtered = items.stream()
                    .filter(p -> country.equalsIgnoreCase(p.country()))
                    .toList();
            if (!filtered.isEmpty()) {
                pageConsumer.accept(filtered);
            }
            total += filtered.size();
            log.info("Fetched page {}/{} ({} items, {} kept; total kept so far: {})",
                    page,
                    response.total_pages() == null ? "?" : response.total_pages(),
                    items.size(), filtered.size(), total);
            if (!hasNextPage(response, page, items.size())) {
                break;
            }
            page++;
        }
        return total;
    }

    private boolean hasNextPage(PointsResponse response, int currentPage, int itemsOnPage) {
        // Primary signal: total_pages from the API (current real-world response).
        if (response.total_pages() != null) {
            return currentPage < response.total_pages();
        }
        // Fallback for any future schema that re-introduces HAL-style links.
        if (response._links() != null
                && response._links().next() != null
                && response._links().next().href() != null
                && !response._links().next().href().isBlank()) {
            return true;
        }
        // Last-resort heuristic: a partially-filled page implies we've hit the end.
        return itemsOnPage >= props.perPage();
    }

    PointsResponse fetchPageWithRetry(String country, int page) {
        int attempt = 0;
        while (true) {
            try {
                return fetchPage(country, page);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status >= 500 || status == 429;
                if (!retryable || attempt >= props.maxRetries()) {
                    throw new InPostApiException(
                            "Page %d failed (HTTP %d) after %d attempt(s)".formatted(page, status, attempt + 1), e);
                }
                sleepBackoff(attempt, "HTTP %d".formatted(status));
                attempt++;
            } catch (ResourceAccessException e) {
                if (attempt >= props.maxRetries()) {
                    throw new InPostApiException(
                            "Page %d transport failure after %d attempt(s)".formatted(page, attempt + 1), e);
                }
                sleepBackoff(attempt, e.getClass().getSimpleName());
                attempt++;
            }
        }
    }

    private PointsResponse fetchPage(String country, int page) {
        String uri = UriComponentsBuilder.fromUriString(props.pointsPath())
                .queryParam("country", country)
                .queryParam("page", page)
                .queryParam("per_page", props.perPage())
                .build()
                .toUriString();
        return restClient.get().uri(uri).retrieve().body(PointsResponse.class);
    }

    private void sleepBackoff(int attempt, String reason) {
        long sleep = props.retryBackoffMs() * (1L << attempt);
        log.warn("Transient error ({}); retrying in {} ms (attempt {} of {})",
                reason, sleep, attempt + 1, props.maxRetries());
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InPostApiException("Interrupted during retry backoff", ie);
        }
    }
}
