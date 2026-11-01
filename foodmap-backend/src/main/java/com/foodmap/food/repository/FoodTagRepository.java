package com.foodmap.food.repository;

import com.foodmap.food.domain.FoodTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FoodTagRepository extends JpaRepository<FoodTag, Integer> {

    Optional<FoodTag> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
