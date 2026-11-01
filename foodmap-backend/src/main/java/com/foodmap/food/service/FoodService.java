package com.foodmap.food.service;

import com.foodmap.common.dto.PageResponse;
import com.foodmap.common.exception.ResourceNotFoundException;
import com.foodmap.food.dto.FoodDTO;
import com.foodmap.food.dto.FoodSummaryDTO;
import com.foodmap.food.mapper.FoodMapper;
import com.foodmap.food.repository.FoodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FoodService {

    private final FoodRepository foodRepository;
    private final FoodMapper foodMapper;

    /**
     * Trigram autocomplete on food name — used by the SearchBar dropdown.
     *
     * IMPORTANT: Pageable is UNSORTED. Ranking happens inside the SQL query
     * (similarity DESC, popularity_score DESC). If we passed a Spring Sort
     * here, Spring Data would append an ORDER BY to the native query and
     * fight the in-query ranking.
     */
    public PageResponse<FoodSummaryDTO> search(String query, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 20));
        var resultPage = foodRepository.searchByName(query.trim(), pageable);
        return PageResponse.of(
                resultPage.getContent().stream().map(foodMapper::toSummary).toList(),
                resultPage
        );
    }

    /** Fetch a single food by its surrogate PK. */
    public FoodDTO getById(Long id) {
        var food = foodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Food not found: " + id));
        return foodMapper.toDTO(food);
    }

    /** Fetch a single food by its URL-safe slug (e.g. "pho-bo-tai"). */
    public FoodDTO getBySlug(String slug) {
        var food = foodRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Food not found: " + slug));
        return foodMapper.toDTO(food);
    }
}
