package com.foodmap.review.controller;

import com.foodmap.common.dto.ApiResponse;
import com.foodmap.common.dto.PageResponse;
import com.foodmap.review.dto.CreateReviewRequest;
import com.foodmap.review.dto.ReviewDTO;
import com.foodmap.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/restaurants/{restaurantId}/reviews")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Create and browse restaurant reviews")
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * GET /api/v1/restaurants/{restaurantId}/reviews?page=0&size=10
     *
     * Public — no authentication required.
     * Returns reviews newest-first with embedded user and food projections.
     */
    @GetMapping
    @Operation(summary = "List reviews for a restaurant")
    public ResponseEntity<ApiResponse<PageResponse<ReviewDTO>>> list(
            @PathVariable Long restaurantId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.listForRestaurant(restaurantId, page, size)));
    }

    /**
     * POST /api/v1/restaurants/{restaurantId}/reviews
     *
     * Authenticated — JWT required.
     * One general review per (user, restaurant). One dish review per (user, restaurant, food).
     * Returns HTTP 201 with the created ReviewDTO.
     */
    @PostMapping
    @Operation(summary = "Create a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ApiResponse<ReviewDTO>> create(
            @PathVariable Long restaurantId,
            @Valid @RequestBody CreateReviewRequest request
    ) {
        ReviewDTO created = reviewService.create(restaurantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * DELETE /api/v1/restaurants/{restaurantId}/reviews/{reviewId}
     *
     * Authenticated — JWT required. Only the review author or an ADMIN may delete.
     * Returns HTTP 204 No Content on success.
     */
    @DeleteMapping("/{reviewId}")
    @Operation(summary = "Delete a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> delete(
            @PathVariable Long restaurantId,
            @PathVariable Long reviewId
    ) {
        reviewService.delete(restaurantId, reviewId);
        return ResponseEntity.noContent().build();
    }
}
