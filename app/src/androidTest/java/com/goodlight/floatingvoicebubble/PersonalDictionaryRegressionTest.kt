package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.dictionary.DictionarySort
import com.goodlight.floatingvoicebubble.dictionary.DictionaryTerm
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalDictionaryRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun renameIsAtomicAndPreservesRuntimeUsage() {
        val suffix = System.nanoTime().toString()
        val old = "__fvb_old_$suffix"
        val renamed = "__fvb_new_$suffix"
        PersonalDictionary(context).use { dictionary ->
            try {
                dictionary.upsert(DictionaryTerm(old, "おーるど", listOf("OLD-$suffix"), weight = 500))
                dictionary.markUsed(listOf(old, old))

                dictionary.save(
                    originalTerm = old,
                    term = DictionaryTerm(renamed, "にゅー", listOf("NEW-$suffix"), weight = 1000),
                )

                assertNull(dictionary.get(old))
                val saved = dictionary.get(renamed)
                assertNotNull(saved)
                assertEquals("にゅー", saved!!.reading)
                assertEquals(listOf("NEW-$suffix"), saved.aliases)
                assertEquals(1000, saved.weight)
                assertEquals(1, saved.useCount)
            } finally {
                dictionary.delete(old)
                dictionary.delete(renamed)
            }
        }
    }

    @Test
    fun renameCollisionRollsBackWithoutDestroyingEitherEntry() {
        val suffix = System.nanoTime().toString()
        val first = "__fvb_first_$suffix"
        val second = "__fvb_second_$suffix"
        PersonalDictionary(context).use { dictionary ->
            try {
                dictionary.upsert(DictionaryTerm(first, weight = 100))
                dictionary.upsert(DictionaryTerm(second, weight = 500))

                val failed = runCatching {
                    dictionary.save(first, DictionaryTerm(second, reading = "collision", weight = 1000))
                }.isFailure

                assertTrue(failed)
                assertNotNull(dictionary.get(first))
                assertEquals(500, dictionary.get(second)!!.weight)
                assertFalse(dictionary.get(second)!!.reading == "collision")
            } finally {
                dictionary.delete(first)
                dictionary.delete(second)
            }
        }
    }

    @Test
    fun searchFindsAliasesCaseInsensitivelyAndSupportsUsefulSorts() {
        val suffix = System.nanoTime().toString()
        val high = "__fvb_high_$suffix"
        val used = "__fvb_used_$suffix"
        PersonalDictionary(context).use { dictionary ->
            try {
                dictionary.upsert(DictionaryTerm(high, aliases = listOf("MiXeD-$suffix"), weight = 1000))
                dictionary.upsert(DictionaryTerm(used, aliases = listOf("other-$suffix"), weight = 100))
                dictionary.markUsed(listOf(used))

                val aliasResult = dictionary.search("mixed-$suffix", limit = 20)
                assertTrue(aliasResult.any { it.term == high })

                val priority = dictionary.search("__fvb_", limit = 100, sort = DictionarySort.PRIORITY)
                assertTrue(priority.indexOfFirst { it.term == high } < priority.indexOfFirst { it.term == used })

                val mostUsed = dictionary.search("__fvb_", limit = 100, sort = DictionarySort.MOST_USED)
                assertTrue(mostUsed.indexOfFirst { it.term == used } < mostUsed.indexOfFirst { it.term == high })
            } finally {
                dictionary.delete(high)
                dictionary.delete(used)
            }
        }
    }

    @Test
    fun paginationDoesNotDuplicateOrDropRowsAcrossBoundary() {
        val prefix = "__fvb_page_${System.nanoTime()}_"
        val terms = (0 until 125).map { "$prefix${it.toString().padStart(3, '0')}" }
        PersonalDictionary(context).use { dictionary ->
            try {
                terms.forEach { dictionary.upsert(DictionaryTerm(it, weight = 100)) }
                val first = dictionary.search(prefix, limit = 100, offset = 0, sort = DictionarySort.TERM)
                val second = dictionary.search(prefix, limit = 100, offset = 100, sort = DictionarySort.TERM)
                val combined = first + second

                assertEquals(100, first.size)
                assertEquals(25, second.size)
                assertEquals(125, combined.map { it.term }.distinct().size)
                assertEquals(terms, combined.map { it.term })
            } finally {
                terms.forEach(dictionary::delete)
            }
        }
    }
}
