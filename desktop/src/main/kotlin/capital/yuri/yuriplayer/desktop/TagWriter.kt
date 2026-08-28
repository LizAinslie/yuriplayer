package capital.yuri.yuriplayer.desktop

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

/** Writes tags + embedded cover into a local audio file. */
object TagWriter {
    data class SongEdit(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: Int? = null,
        val genre: String? = null,
        val trackNumber: Int? = null
    )

    fun write(file: File, edit: SongEdit, coverJpeg: ByteArray? = null): Result<Unit> = runCatching {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        fun set(key: FieldKey, value: String?) {
            if (value == null) return
            if (value.isBlank()) tag.deleteField(key) else tag.setField(key, value)
        }
        set(FieldKey.TITLE, edit.title)
        set(FieldKey.ARTIST, edit.artist)
        set(FieldKey.ALBUM, edit.album)
        set(FieldKey.ALBUM_ARTIST, edit.albumArtist)
        set(FieldKey.YEAR, edit.year?.toString())
        set(FieldKey.GENRE, edit.genre)
        set(FieldKey.TRACK, edit.trackNumber?.toString())
        if (coverJpeg != null && coverJpeg.isNotEmpty()) {
            runCatching {
                val tmp = File.createTempFile("yuri-cover-", ".jpg")
                tmp.writeBytes(coverJpeg)
                tmp.deleteOnExit()
                val art = ArtworkFactory.createArtworkFromFile(tmp)
                tag.deleteArtworkField()
                tag.setField(art)
            }
        }
        audio.commit()
    }
}
