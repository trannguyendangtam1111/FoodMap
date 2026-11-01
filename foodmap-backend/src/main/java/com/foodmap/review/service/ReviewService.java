package com.foodmap.review.service;

import com.foodmap.common.dto.PageResponse;
import com.foodmap.common.exception.BadRequestException;
import com.foodmap.common.exception.BusinessException;
import com.foodmap.common.exception.ResourceNotFoundException;
import com.foodmap.common.exception.UnauthorizedException;
import com.foodmap.food.domain.Food;
import com.foodmap.food.dto.FoodSummaryDTO;
import com.foodmap.food.repository.FoodRepository;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import com.foodmap.review.domain.Review;
import com.foodmap.review.dto.CreateReviewRequest;
import com.foodmap.review.dto.ReviewDTO;
import com.foodmap.review.dto.ReviewUserDTO;
import com.foodmap.review.repository.ReviewRepository;
import com.foodmap.security.SecurityUtils;
import com.foodmap.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository        reviewRepository;
    private final RestaurantRepository    restaurantRepository;
    private final RestaurantFoodRepository restaurantFoodRepository;
    private final FoodRepository          foodRepository;
    private final SecurityUtils           securityUtils;

    // ── List ──────────────────────────────────────────────────────────────────

    /**
     * Returns a paginated list of reviews for a restaurant, newest first.
     * Uses a JOIN FETCH query to avoid N+1 on user and food.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReviewDTO> listForRestaurant(Long restaurantId, int page, int size) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant not found: " + restaurantId);
        }
        var pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Review> reviewPage = reviewRepository.findByRestaurantIdWithDetails(restaurantId, pageable);
        return PageResponse.of(
                reviewPage.getContent().stream().map(this::toDTO).toList(),
                reviewPage
        );
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a review for a restaurant, enforcing three business rules:
     *
     * <ol>
     *   <li>The food being reviewed (if any) must actually be served at this restaurant.</li>
     *   <li>One general review per (user, restaurant). One dish review per (user, restaurant, food).</li>
     *   <li>The denormalized avgRating / reviewCount on the restaurant is recalculated after
     *       every write, protected by a pessimistic row-level lock on the restaurant row.</li>
     * </ol>
     *
     * <p><b>Locking strategy</b>: {@code findByIdForUpdate} acquires a PostgreSQL
     * {@code SELECT ... FOR UPDATE} lock on the restaurant row at the start of this
     * transaction. Any concurrent review submission for the same restaurant blocks here
     * until this transaction commits. This ensures {@code recalculateRatingStats}'s
     * correlated AVG subquery always sees all committed reviews — preventing the race
     * where two concurrent writes both recalculate from a stale count and the last writer
     * overwrites the first with an incorrect aggregate.</p>
     *
     * <p><b>Duplicate safety net</b>: the application-level duplicate check (SELECT before
     * INSERT) handles the common case. The partial unique indexes on {@code reviews}
     * (V3 migration) are the DB-level safety net for the narrow concurrent window where
     * two requests both pass the SELECT check. If the DB constraint fires, Spring raises
     * {@link DataIntegrityViolationException}, which is caught here and re-thrown as a
     * 409 Conflict.</p>
     */
    @Transactional
    public ReviewDTO create(Long restaurantId, CreateReviewRequest request) {
        User currentUser = securityUtils.getCurrentUser()
                .orElseThrow(() -> new UnauthorizedException("Authentication required to post a review."));

        // ── Acquire row-level lock on the restaurant ─────────────────────────
        // Must happen before any read of review data so that the lock covers
        // the entire window between the duplicate check and the stats update.
        Restaurant restaurant = restaurantRepository.findByIdForUpdate(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));

        // ── Duplicate guard (application layer, fast path) ───────────────────
        // The DB partial unique indexes are the safety net for concurrent requests
        // that race past this check. For the common case, this gives the user a
        // descriptive error message instead of a generic constraint violation.
        if (request.getFoodId() == null) {
            reviewRepository.findGeneralReview(currentUser.getId(), restaurantId)
                    .ifPresent(r -> { throw new BadRequestException(
                            "You have already reviewed this restaurant."); });
        } else {
            reviewRepository
                    .findByUserIdAndRestaurantIdAndFoodId(
                            currentUser.getId(), restaurantId, request.getFoodId())
                    .ifPresent(r -> { throw new BadRequestException(
                            "You have already reviewed this dish at this restaurant."); });
        }

        // ── Resolve and validate optional food ───────────────────────────────
        Food food = null;
        if (request.getFoodId() != null) {
            food = foodRepository.findById(request.getFoodId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Food not found: " + request.getFoodId()));

            // Integrity check: the food must be on the menu at this restaurant.
            // Without this, a user could submit a review for a food that has no
            // relationship to the restaurant — the review would be semantically
            // invalid (food_id points to a dish served elsewhere entirely).
            if (!restaurantFoodRepository.existsByRestaurantIdAndFoodId(restaurantId, request.getFoodId())) {
                throw new BadRequestException(
                        "This dish is not listed on the menu of this restaurant.");
            }
        }

        // ── Persist ─────────────────────────────────────────────────────────
        Review review = Review.builder()
                .user(currentUser)
                .restaurant(restaurant)
                .food(food)
                .rating(request.getRating())
                .comment(sanitise(request.getComment()))
                .build();

        try {
            review = reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // The partial unique index fired — a concurrent request inserted a duplicate
            // review in the narrow window between our SELECT check and this INSERT.
            // The transaction is already marked for rollback at this point; re-throwing
            // a BusinessException causes Spring to roll back cleanly and return 409.
            log.warn("Concurrent duplicate review blocked for user_id={} restaurant_id={} food_id={}",
                    currentUser.getId(), restaurantId, request.getFoodId());
            throw new BusinessException(
                    "DUPLICATE_REVIEW",
                    request.getFoodId() == null
                            ? "You have already reviewed this restaurant."
                            : "You have already reviewed this dish at this restaurant.",
                    HttpStatus.CONFLICT
            );
        }

        // ── Recalculate denormalized stats ───────────────────────────────────
        // saveAndFlush above already flushed — the new review row is visible to
        // the correlated AVG/COUNT subqueries in recalculateRatingStats.
        // The restaurant row lock (held since the top of this method) ensures no
        // other transaction can run its own recalculation before this one commits.
        restaurantRepository.recalculateRatingStats(restaurantId);

        log.info("Review {} created by user {} for restaurant {}", review.getId(),
                currentUser.getId(), restaurantId);

        return toDTO(review);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Deletes a review. Only the author or an ADMIN may delete.
     *
     * Acquires the same pessimistic lock as {@link #create} so that the
     * post-delete recalculation is also race-free.
     */
    @Transactional
    public void delete(Long restaurantId, Long reviewId) {
        User currentUser = securityUtils.getCurrentUser()
                .orElseThrow(() -> new UnauthorizedException("Authentication required."));

        // Acquire the restaurant lock before loading the review to maintain
        // consistent lock-ordering with create() and avoid potential deadlocks.
        restaurantRepository.findByIdForUpdate(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + reviewId));

        if (!review.getRestaurant().getId().equals(restaurantId)) {
            throw new ResourceNotFoundException("Review not found: " + reviewId);
        }

        boolean isOwner = review.getUser().getId().equals(currentUser.getId());
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        if (!isOwner && !isAdmin) {
            throw new UnauthorizedException("You are not allowed to delete this review.");
        }

        reviewRepository.delete(review);
        reviewRepository.flush();
        restaurantRepository.recalculateRatingStats(restaurantId);

        log.info("Review {} deleted by user {}", reviewId, currentUser.getId());
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private ReviewDTO toDTO(Review review) {
        FoodSummaryDTO foodDTO = null;
        if (review.getFood() != null) {
            Food f = review.getFood();
            foodDTO = FoodSummaryDTO.builder()
                    .id(f.getId())
                    .slug(f.getSlug())
                    .name(f.getName())
                    .cuisine(f.getCuisine())
                    .photoUrl(f.getPhotoUrl())
                    .tags(f.getTags().stream()
                            .map(t -> t.getName())
                            .sorted()
                            .collect(Collectors.toList()))
                    .build();
        }

        User u = review.getUser();
        ReviewUserDTO userDTO = ReviewUserDTO.builder()
                .id(u.getId())
                .displayName(u.getDisplayName())
                .avatarUrl(u.getAvatarUrl())
                .build();

        return ReviewDTO.builder()
                .id(review.getId())
                .rating(review.getRating())
                .comment(review.getComment())
                .food(foodDTO)
                .user(userDTO)
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }

    /** Trims whitespace and converts blank strings to null. */
    private String sanitise(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
