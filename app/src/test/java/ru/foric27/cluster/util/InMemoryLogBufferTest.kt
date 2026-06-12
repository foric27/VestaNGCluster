package ru.foric27.cluster.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InMemoryLogBufferTest {

    companion object {
        private const val VERBOSE = 2
        private const val DEBUG = 3
        private const val INFO = 4
        private const val WARN = 5
        private const val ERROR = 6
    }

    @Before
    fun setUp() {
        InMemoryLogBuffer.clear()
    }

    @Test
    fun `clear results in size 0`() {
        InMemoryLogBuffer.append(INFO, "tag", "msg")
        InMemoryLogBuffer.clear()
        assertEquals(0, InMemoryLogBuffer.size())
    }

    @Test
    fun `append INFO increases size`() {
        InMemoryLogBuffer.append(INFO, "tag", "message")
        assertEquals(1, InMemoryLogBuffer.size())
    }

    @Test
    fun `append VERBOSE is ignored`() {
        InMemoryLogBuffer.append(VERBOSE, "tag", "message")
        assertEquals(0, InMemoryLogBuffer.size())
    }

    @Test
    fun `append DEBUG is ignored`() {
        InMemoryLogBuffer.append(DEBUG, "tag", "message")
        assertEquals(0, InMemoryLogBuffer.size())
    }

    @Test
    fun `append WARN increases size`() {
        InMemoryLogBuffer.append(WARN, "tag", "message")
        assertEquals(1, InMemoryLogBuffer.size())
    }

    @Test
    fun `append ERROR increases size`() {
        InMemoryLogBuffer.append(ERROR, "tag", "message")
        assertEquals(1, InMemoryLogBuffer.size())
    }

    @Test
    fun `toList returns copy`() {
        InMemoryLogBuffer.append(INFO, "tag", "msg")
        val list = InMemoryLogBuffer.toList()
        list.clear()
        assertEquals(1, InMemoryLogBuffer.size())
    }

    @Test
    fun `multiple appends preserve order`() {
        InMemoryLogBuffer.append(INFO, "t", "first")
        InMemoryLogBuffer.append(WARN, "t", "second")
        InMemoryLogBuffer.append(ERROR, "t", "third")
        val list = InMemoryLogBuffer.toList()
        assertEquals(3, list.size)
        assertEquals("first", list[0].message)
        assertEquals("second", list[1].message)
        assertEquals("third", list[2].message)
    }

    @Test
    fun `LogLine toString format`() {
        InMemoryLogBuffer.append(INFO, "MyTag", "hello")
        val line = InMemoryLogBuffer.toList().first()
        val str = line.toString()
        assertTrue(str.contains("I/MyTag: hello"))
    }
}
