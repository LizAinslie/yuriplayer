package capital.yuri.yuriplayer.data

// Moved to :core (commonMain), same package. The Android client wires the
// shared `CatalogRepository` with a platform [LocalScanner] (MusicRepository)
// and a [CoverCandidateBuilder] (the Android filesystem/SAF-aware builder).

import capital.yuri.yuriplayer.data.CoverCandidateBuilder
import capital.yuri.yuriplayer.data.LocalScanner
import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository

/**
 * Android-specific construction of the shared [capital.yuri.yuriplayer.data.CatalogRepository].
 */
fun androidCatalogRepository(
    dao: CatalogDao,
    musicRepository: MusicRepository,
    sourceInstances: SourceInstanceRepository
): capital.yuri.yuriplayer.data.CatalogRepository =
    capital.yuri.yuriplayer.data.CatalogRepository(
        dao = dao,
        sourceInstances = sourceInstances,
        localScanner = LocalScanner { musicRepository.scanLibrary() },
        coverCandidateBuilder = CoverCandidateBuilder { songs, coverPath, coverUrl ->
            CoverCandidates.build(songs, coverPath, coverUrl)
        }
    )
