package com.moneybook.shared.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CategoryMatcherTest {
    private val options = listOf(
        "🍜 Food", "👬🏻 Social Life", "🐶 Pets", "🚖 Transport", "🧥 Apparel", "🧘🏼 Health/Fitness", "🎁 Gift", "Other",
        "charita", "vitaminy", "entertainment", "electronics",
    ).mapIndexed { i, name -> CategoryOption("c$i", name) }

    private fun match(name: String) = CategoryMatcher.suggest(name, options)?.name

    @Test fun `old app names find their current counterparts`() {
        assertEquals("🍜 Food", match("Food"))
        assertEquals("🚖 Transport", match("Transportation"))
        assertEquals("charita", match("Charity"))
        assertEquals("vitaminy", match("Vitamins"))
        assertEquals("👬🏻 Social Life", match("Social"))
        assertEquals("🐶 Pets", match("Pet"))
        assertEquals("🧘🏼 Health/Fitness", match("Health"))
        assertEquals("Other", match("Others"))
        assertEquals("entertainment", match("Entertainment"))
        assertEquals("electronics", match("Electronics "))
    }

    @Test fun `unrelated names get no suggestion`() {
        assertNull(match("Clothing"))
        assertNull(match("Sport"))
        assertNull(match("Book"))
        assertNull(match("Office"))
        assertNull(match("vrácení zálohy"))
    }

    @Test fun `normalize strips emoji, accents and punctuation`() {
        assertEquals("health fitness", CategoryMatcher.normalize("🧘🏼 Health/Fitness"))
        assertEquals("vraceni zalohy", CategoryMatcher.normalize(" vrácení zálohy "))
    }

    @Test fun `similarity scores`() {
        assertEquals(1.0, CategoryMatcher.similarity("Food", "🍜 food"))
        assertEquals(0.9, CategoryMatcher.similarity("Transportation", "🚖 Transport"))
        assertEquals(1.0 - 1.0 / 7, CategoryMatcher.similarity("Charity", "charita"), 1e-9)
        assertEquals(0.0, CategoryMatcher.similarity("🍜", "Food"))
    }
}
