package com.saltbridge.plugin

import com.saltbridge.core.HostState
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint as Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaMapperTest {

    private fun media(
        id: String = "",
        title: String = "晴天",
        artist: String = "周杰伦",
        album: String = "叶惠美",
        albumArtist: String = "周杰伦",
        path: String = "C:\\Music\\qingtian.flac",
        duration: Long = 269_000,
    ) = Host.MediaItem(title, artist, album, albumArtist, path, id, 0, "", 0, duration, false, false)

    @Test
    fun `maps host media item to core track`() {
        val track = MediaMapper.toTrack(media(id = "lib-1"))

        assertEquals("lib-1", track.id)
        assertEquals("晴天", track.title)
        assertEquals("周杰伦", track.artist)
        assertEquals("叶惠美", track.album)
        assertEquals(269_000L, track.durationMs)
        assertTrue(track.pathHash.isNotEmpty())
    }

    @Test
    fun `blank id becomes null`() {
        assertNull(MediaMapper.toTrack(media(id = "")).id)
        assertNull(MediaMapper.toTrack(media(id = "   ")).id)
    }

    @Test
    fun `zero duration becomes null`() {
        assertNull(MediaMapper.toTrack(media(duration = 0)).durationMs)
    }

    @Test
    fun `path hash never contains the raw path and is stable`() {
        val path = "C:\\Music\\qingtian.flac"
        val hash = MediaMapper.pathHash(path)

        assertEquals(16, hash.length)
        assertTrue(!hash.contains("Music"))
        assertTrue(!hash.contains("qingtian"))
        assertEquals(hash, MediaMapper.pathHash(path))
        assertNotEquals(hash, MediaMapper.pathHash("C:\\Music\\other.flac"))
    }

    @Test
    fun `empty path gives empty hash`() {
        assertEquals("", MediaMapper.pathHash(""))
    }

    @Test
    fun `maps lyrics including cells and translation`() {
        val host = listOf(
            Host.LyricsLine(
                startTime = 5_000,
                endTime = 8_000,
                lyricsCells = listOf(
                    Host.LyricsLine.Cell(5_000, 5_300, "故"),
                    Host.LyricsLine.Cell(5_300, 5_600, "事"),
                ),
                pureMainText = "故事的小黄花",
                pureSubText = "the little yellow flowers",
            ),
        )

        val lines = MediaMapper.toLyrics(host)

        assertEquals(1, lines.size)
        assertEquals(5_000L, lines[0].startMs)
        assertEquals(8_000L, lines[0].endMs)
        assertEquals("故事的小黄花", lines[0].text)
        assertEquals("the little yellow flowers", lines[0].translation)
        assertEquals(2, lines[0].cells.size)
        assertEquals("故", lines[0].cells[0].text)
        assertEquals("word", lines[0].timing)
    }

    @Test
    fun `lyrics without cells is line timing`() {
        val host = listOf(
            Host.LyricsLine(0, 1_000, emptyList(), "no cells", null),
        )
        assertEquals("line", MediaMapper.toLyrics(host).single().timing)
    }

    @Test
    fun `state mapping covers all four`() {
        assertEquals(HostState.IDLE, MediaMapper.toCoreState(Host.State.Idle))
        assertEquals(HostState.BUFFERING, MediaMapper.toCoreState(Host.State.Buffering))
        assertEquals(HostState.READY, MediaMapper.toCoreState(Host.State.Ready))
        assertEquals(HostState.ENDED, MediaMapper.toCoreState(Host.State.Ended))
    }
}
