package org.koitharu.kotatsu.tracker.domain

import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GetTracksUseCase @Inject constructor(
	private val repository: TrackingRepository,
) {

	suspend operator fun invoke(limit: Int): List<MangaTracking> {
		repository.updateTracks()
		return repository.getTracks(
			offset = 0,
			limit = limit,
			// Skip tracks with no fresh chapters or user activity in the last MAX_INACTIVE_DAYS so the
			// periodic worker doesn't burn requests on long-stalled or abandoned series.
			minActivityTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_INACTIVE_DAYS),
		)
	}

	private companion object {
		const val MAX_INACTIVE_DAYS = 90L
	}
}
