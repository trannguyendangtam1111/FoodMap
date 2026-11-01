package com.foodmap.restaurant.service;

import com.foodmap.config.properties.AppProperties;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.integration.goong.mapper.GoongPlaceMapper;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.repository.RestaurantRepository;
import com.foodmap.restaurant.service.RestaurantSyncService.PlaceSyncResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterises the cache path the ingestion locality check relies on:
 * a fresh cached restaurant is returned with its coordinates and Goong
 * Place Detail is not called.
 */
class RestaurantSyncServiceCacheTest {

    @Test
    void freshCachedRestaurant_isReturnedWithCoordinates_withoutPlaceDetail() {
        GoongClient goongClient = mock(GoongClient.class);
        RestaurantRepository repository = mock(RestaurantRepository.class);
        RestaurantSyncService service = new RestaurantSyncService(
            goongClient, mock(GoongPlaceMapper.class), repository, new AppProperties());

        Restaurant cached = Restaurant.builder()
            .goongPlaceId("place-1")
            .latitude(10.7790)
            .longitude(106.7009)
            .lastSyncedAt(Instant.now())
            .build();
        when(repository.findByGoongPlaceId("place-1")).thenReturn(Optional.of(cached));

        PlaceSyncResult result = service.ensureFreshPlace("place-1");

        assertThat(result.goongCalled()).isFalse();
        assertThat(result.restaurant()).containsSame(cached);
        assertThat(result.restaurant().get().getLatitude()).isEqualTo(10.7790);
        verify(goongClient, never()).placeDetail(anyString());
    }
}
