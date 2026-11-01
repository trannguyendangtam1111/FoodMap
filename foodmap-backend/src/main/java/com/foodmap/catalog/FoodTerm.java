package com.foodmap.catalog;

import com.foodmap.common.text.VietnameseText;

import java.util.List;

/**
 * One matchable phrase of a food entry (a keyword, a suppressor, or the
 * label), pre-tokenized once at load time.
 *
 * @param text         as written in the catalog
 * @param asciiSafe    whether a text written without diacritics may match the folded form
 * @param tokens       normalized tokens (accents kept)
 * @param foldedTokens tokens with diacritics removed
 */
public record FoodTerm(String text, boolean asciiSafe, List<String> tokens, List<String> foldedTokens) {

    public static FoodTerm of(String text, boolean asciiSafe) {
        List<String> tokens = VietnameseText.tokenize(text);
        List<String> folded = tokens.stream().map(VietnameseText::fold).toList();
        return new FoodTerm(text, asciiSafe, tokens, folded);
    }
}
