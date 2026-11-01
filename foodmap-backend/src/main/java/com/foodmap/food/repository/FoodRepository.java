package com.foodmap.food.repository;

import com.foodmap.food.domain.Food;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FoodRepository extends JpaRepository<Food, Long> {

    Optional<Food> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * Trigram autocomplete on food name.
     *
     * HOW IT WORKS
     *   - `immutable_unaccent(lower(...))` strips diacritics and lowercases
     *     both the column expression AND the input, so "pho" matches "Phở".
     *   - `%` is the pg_trgm similarity operator. It returns true when the
     *     trigram similarity exceeds pg_trgm.similarity_threshold (default 0.3).
     *     The GIN index `idx_foods_name_trgm` accelerates this in O(log n)-ish
     *     time instead of the seq scan that `LIKE '%x%'` forced.
     *   - The WHERE expression must be IDENTICAL to the index expression,
     *     otherwise the planner cannot use the index. Do not refactor.
     *   - ORDER BY uses `similarity()` (a numeric score, 0..1) so the most
     *     relevant matches come first, with popularityScore as a tie-breaker.
     *
     * PAGEABLE
     *   The service passes Pageable.unsorted() — Sort would be appended to the
     *   query and conflict with the relevance ORDER BY we have here.
     *
     * NATIVE QUERY
     *   The `%` operator and similarity() are not JPQL — we drop to SQL.
     *   The `countQuery` mirrors the WHERE so Spring Data can compute totals
     *   using the same trigram index (cheap; no seq scan).
     */
    @Query(
        value = """
            SELECT * FROM foods
            WHERE immutable_unaccent(lower(name)) % immutable_unaccent(lower(:query))
            ORDER BY similarity(immutable_unaccent(lower(name)),
                                immutable_unaccent(lower(:query))) DESC,
                     popularity_score DESC
            """,
        countQuery = """
            SELECT count(*) FROM foods
            WHERE immutable_unaccent(lower(name)) % immutable_unaccent(lower(:query))
            """,
        nativeQuery = true
    )
    Page<Food> searchByName(@Param("query") String query, Pageable pageable);
}
