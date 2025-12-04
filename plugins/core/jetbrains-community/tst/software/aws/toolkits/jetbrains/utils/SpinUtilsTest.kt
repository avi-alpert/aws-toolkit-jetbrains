// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.utils

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive unit tests for SpinUtils utility functions.
 * Tests spinUntil and spinUntilValue functions with various scenarios including:
 * - Successful condition matching
 * - Timeout scenarios
 * - Edge cases with null values
 * - Custom polling intervals
 */
class SpinUtilsTest {

    @Test
    fun `spinUntil completes successfully when condition is met immediately`() {
        var invocations = 0
        spinUntil(Duration.ofSeconds(1)) {
            invocations++
            true
        }
        assertThat(invocations).isEqualTo(1)
    }

    @Test
    fun `spinUntil completes successfully when condition is met after several attempts`() {
        val counter = AtomicInteger(0)
        spinUntil(Duration.ofSeconds(2)) {
            counter.incrementAndGet() >= 3
        }
        assertThat(counter.get()).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `spinUntil throws TimeoutException when condition is never met`() {
        assertThatThrownBy {
            spinUntil(Duration.ofMillis(SHORT_TIMEOUT_MS)) {
                false
            }
        }
            .isInstanceOf(TimeoutException::class.java)
            .hasMessageContaining("Condition not reached within PT0.2S")
    }

    @Test
    fun `spinUntil respects custom interval parameter`() {
        val startTime = System.nanoTime()
        val counter = AtomicInteger(0)
        
        spinUntil(Duration.ofSeconds(2), Duration.ofMillis(POLLING_INTERVAL_MS * 2)) {
            counter.incrementAndGet() >= 3
        }
        
        val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
        // Should take at least 400ms (2 intervals of 200ms) but less than timeout
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(400)
        assertThat(elapsedMillis).isLessThan(2000)
    }

    @Test
    fun `spinUntil with very short timeout fails quickly`() {
        val startTime = System.nanoTime()
        
        assertThatThrownBy {
            spinUntil(Duration.ofMillis(50)) {
                false
            }
        }.isInstanceOf(TimeoutException::class.java)
        
        val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
        // Should timeout within a reasonable time frame
        assertThat(elapsedMillis).isLessThan(500)
    }

    @Test
    fun `spinUntilValue returns value when found immediately`() {
        val result = spinUntilValue(Duration.ofSeconds(1)) {
            "expected"
        }
        assertThat(result).isEqualTo("expected")
    }

    @Test
    fun `spinUntilValue returns value when found after several attempts`() {
        val counter = AtomicInteger(0)
        val result = spinUntilValue(Duration.ofSeconds(2)) {
            val count = counter.incrementAndGet()
            if (count >= 3) {
                "success-$count"
            } else {
                null
            }
        }
        assertThat(result).startsWith("success-")
        assertThat(counter.get()).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `spinUntilValue throws TimeoutException when value never becomes non-null`() {
        assertThatThrownBy {
            spinUntilValue(Duration.ofMillis(200)) {
                null
            }
        }
            .isInstanceOf(TimeoutException::class.java)
            .hasMessageContaining("Condition not reached within PT0.2S")
    }

    @Test
    fun `spinUntilValue respects custom interval parameter`() {
        val startTime = System.nanoTime()
        val counter = AtomicInteger(0)
        
        val result = spinUntilValue(Duration.ofSeconds(2), Duration.ofMillis(200)) {
            val count = counter.incrementAndGet()
            if (count >= 3) "result" else null
        }
        
        val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
        assertThat(result).isEqualTo("result")
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(400)
    }

    @Test
    fun `spinUntilValue works with different types`() {
        // Test with Integer
        val intResult = spinUntilValue(Duration.ofSeconds(1)) {
            42
        }
        assertThat(intResult).isEqualTo(42)

        // Test with List
        val listResult = spinUntilValue(Duration.ofSeconds(1)) {
            listOf("a", "b", "c")
        }
        assertThat(listResult).containsExactly("a", "b", "c")

        // Test with custom object
        data class TestData(val id: Int, val name: String)
        val objectResult = spinUntilValue(Duration.ofSeconds(1)) {
            TestData(1, "test")
        }
        assertThat(objectResult).isEqualTo(TestData(1, "test"))
    }

    @Test
    fun `spinUntilValue handles edge case with false boolean values`() {
        // Boolean false is not null, so it should return immediately
        val result = spinUntilValue(Duration.ofSeconds(1)) {
            false
        }
        assertThat(result).isFalse
    }

    @Test
    fun `spinUntilValue handles edge case with zero numeric values`() {
        // Zero is not null, so it should return immediately
        val result = spinUntilValue(Duration.ofSeconds(1)) {
            0
        }
        assertThat(result).isEqualTo(0)
    }

    @Test
    fun `spinUntilValue handles edge case with empty string`() {
        // Empty string is not null, so it should return immediately
        val result = spinUntilValue(Duration.ofSeconds(1)) {
            ""
        }
        assertThat(result).isEmpty()
    }

    @Test
    fun `spinUntil handles condition that becomes true near timeout boundary`() {
        val counter = AtomicInteger(0)
        val timeoutMillis = 500L
        
        // Condition will become true just before timeout
        spinUntil(Duration.ofMillis(timeoutMillis)) {
            val count = counter.incrementAndGet()
            // This will succeed after about 300-400ms
            count >= 3
        }
        
        assertThat(counter.get()).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `spinUntilValue handles value that becomes available near timeout boundary`() {
        val counter = AtomicInteger(0)
        val timeoutMillis = 500L
        
        val result = spinUntilValue(Duration.ofMillis(timeoutMillis)) {
            val count = counter.incrementAndGet()
            if (count >= 3) "near-timeout-success" else null
        }
        
        assertThat(result).isEqualTo("near-timeout-success")
        assertThat(counter.get()).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `spinUntil invokes condition multiple times before success`() {
        val invocations = mutableListOf<Long>()
        
        spinUntil(Duration.ofSeconds(2), Duration.ofMillis(100)) {
            invocations.add(System.currentTimeMillis())
            invocations.size >= 5
        }
        
        assertThat(invocations).hasSize(5)
        // Verify that invocations were spaced out
        for (i in 1 until invocations.size) {
            val gap = invocations[i] - invocations[i - 1]
            assertThat(gap).isGreaterThanOrEqualTo(80) // Allow some slack for timing variance
        }
    }

    @Test
    fun `spinUntilValue invokes block multiple times before returning value`() {
        val invocations = mutableListOf<Long>()
        
        val result = spinUntilValue(Duration.ofSeconds(2), Duration.ofMillis(100)) {
            invocations.add(System.currentTimeMillis())
            if (invocations.size >= 5) "completed" else null
        }
        
        assertThat(result).isEqualTo("completed")
        assertThat(invocations).hasSize(5)
    }

    @Test
    fun `spinUntil handles exception thrown within condition block`() {
        var attempts = 0
        assertThatThrownBy {
            spinUntil(Duration.ofSeconds(1)) {
                attempts++
                if (attempts == 2) {
                    throw IllegalStateException("Test exception")
                }
                false
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Test exception")
    }

    @Test
    fun `spinUntilValue handles exception thrown within block`() {
        var attempts = 0
        assertThatThrownBy {
            spinUntilValue(Duration.ofSeconds(1)) {
                attempts++
                if (attempts == 2) {
                    throw IllegalStateException("Test exception in value block")
                }
                null
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Test exception in value block")
    }

    @Test
    fun `spinUntil with minimum viable timeout duration`() {
        // Test with very small but non-zero timeout
        assertThatThrownBy {
            spinUntil(Duration.ofMillis(1)) {
                false
            }
        }.isInstanceOf(TimeoutException::class.java)
    }

    @Test
    fun `spinUntilValue preserves returned value correctly`() {
        // Test that the exact value returned is preserved
        data class ComplexObject(val nested: Map<String, List<Int>>)
        
        val expectedValue = ComplexObject(
            nested = mapOf(
                "key1" to listOf(1, 2, 3),
                "key2" to listOf(4, 5, 6)
            )
        )
        
        val result = spinUntilValue(Duration.ofSeconds(1)) {
            expectedValue
        }
        
        assertThat(result).isEqualTo(expectedValue)
        assertThat(result.nested).containsKeys("key1", "key2")
    }

    private companion object {
        private const val SHORT_TIMEOUT_MS = 200L
        private const val STANDARD_TIMEOUT_MS = 500L
        private const val POLLING_INTERVAL_MS = 100L
    }
}
