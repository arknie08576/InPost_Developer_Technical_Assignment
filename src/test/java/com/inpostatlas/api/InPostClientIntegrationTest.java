package com.inpostatlas.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.inpostatlas.api.dto.PointDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InPostClientIntegrationTest {

    private WireMockServer wireMockServer;
    private InPostClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();

        AtlasApiProperties props = new AtlasApiProperties(
                "http://localhost:" + wireMockServer.port(),
                "/v1/points",
                /* perPage */ 2,
                /* connectTimeoutSeconds */ 5,
                /* readTimeoutSeconds */ 10,
                /* maxRetries */ 2,
                /* retryBackoffMs */ 50L
        );
        client = new InPostClient(props);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void paginatesUsingTotalPagesField() {
        stubPage(1, 2, """
                {"name":"PL01","country":"PL","status":"Operating","location":{"latitude":52.0,"longitude":21.0},
                 "address_details":{"province":"Mazowieckie"},"location_247":true,"easy_access_zone":true},
                {"name":"PL02","country":"PL","status":"Operating","location":{"latitude":52.1,"longitude":21.1},
                 "address_details":{"province":"mazowieckie"}}
                """);

        stubPage(2, 2, """
                {"name":"PL03","country":"PL","status":"Operating","location":{"latitude":50.0,"longitude":19.9},
                 "address_details":{"province":"małopolskie"}}
                """);

        List<PointDto> collected = new ArrayList<>();
        int total = client.fetchByCountry("PL", collected::addAll);

        assertThat(total).isEqualTo(3);
        assertThat(collected).extracting(PointDto::name).containsExactly("PL01", "PL02", "PL03");
        assertThat(collected.get(0).address_details().province()).isEqualTo("Mazowieckie");
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/v1/points"))
                .withQueryParam("page", equalTo("1")));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/v1/points"))
                .withQueryParam("page", equalTo("2")));
    }

    @Test
    void fallsBackToHalLinksWhenTotalPagesAbsent() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/points"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[{\"name\":\"PL10\",\"country\":\"PL\",\"address_details\":{\"province\":\"mazowieckie\"}}]," +
                                "\"_links\":{\"next\":{\"href\":\"http://x/v1/points?page=2\"}}}")));
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/points"))
                .withQueryParam("page", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[{\"name\":\"PL11\",\"country\":\"PL\",\"address_details\":{\"province\":\"mazowieckie\"}}]," +
                                "\"_links\":{\"next\":null}}")));

        List<PointDto> collected = new ArrayList<>();
        int total = client.fetchByCountry("PL", collected::addAll);

        assertThat(total).isEqualTo(2);
        assertThat(collected).extracting(PointDto::name).containsExactly("PL10", "PL11");
    }

    @Test
    void retriesOn5xxThenSucceeds() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/points"))
                .withQueryParam("page", equalTo("1"))
                .inScenario("retry").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("once-failed"));
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/points"))
                .withQueryParam("page", equalTo("1"))
                .inScenario("retry").whenScenarioStateIs("once-failed")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonPage("""
                                {"name":"PL99","country":"PL","status":"Operating",
                                 "address_details":{"province":"pomorskie"}}
                                """, 1))));

        List<PointDto> collected = new ArrayList<>();
        int total = client.fetchByCountry("PL", collected::addAll);

        assertThat(total).isEqualTo(1);
        assertThat(collected.get(0).name()).isEqualTo("PL99");
    }

    @Test
    void givesUpAfterMaxRetries_andThrows() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/points"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.fetchByCountry("PL", page -> {}))
                .isInstanceOf(InPostApiException.class)
                .hasMessageContaining("Page 1");
    }

    @Test
    void clientSideFiltersOutOtherCountries() {
        stubPage(1, 1, """
                {"name":"PL01","country":"PL","status":"Operating",
                 "address_details":{"province":"mazowieckie"}},
                {"name":"FR01","country":"FR","status":"Operating",
                 "address_details":{"province":"Île-de-France"}}
                """);

        List<PointDto> collected = new ArrayList<>();
        int total = client.fetchByCountry("PL", collected::addAll);

        assertThat(total).isEqualTo(1);
        assertThat(collected).extracting(PointDto::name).containsExactly("PL01");
    }

    @Test
    void doesNotRetryOn4xx() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/points"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.fetchByCountry("PL", page -> {}))
                .isInstanceOf(InPostApiException.class)
                .hasMessageContaining("HTTP 404");

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/v1/points")));
    }

    private void stubPage(int page, int totalPages, String itemsJson) {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/points"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonPage(itemsJson, totalPages))));
    }

    private static String jsonPage(String itemsJson, int totalPages) {
        return "{\"items\":[" + itemsJson + "],\"total_pages\":" + totalPages + "}";
    }
}
