package org.koitharu.kotatsu.core.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.ui.worker.DownloadNotificationFactory
import org.koitharu.kotatsu.download.ui.worker.DownloadSlowdownDispatcher
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.local.domain.DeleteReadChaptersUseCase
import org.koitharu.kotatsu.local.domain.MangaLock
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.ui.LocalStorageCleanupWorker
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AppWorkerFactory @Inject constructor(
	@MangaHttpClient private val okHttp: Provider<OkHttpClient>,
	@PageCache private val cache: Provider<LocalStorageCache>,
	private val localMangaRepository: Provider<LocalMangaRepository>,
	private val mangaLock: Provider<MangaLock>,
	private val mangaDataRepository: Provider<MangaDataRepository>,
	private val mangaRepositoryFactory: Provider<MangaRepository.Factory>,
	private val settings: Provider<AppSettings>,
	@LocalStorageChanges private val localStorageChanges: Provider<MutableSharedFlow<LocalManga?>>,
	private val slowdownDispatcher: Provider<DownloadSlowdownDispatcher>,
	private val imageProxyInterceptor: Provider<ImageProxyInterceptor>,
	private val downloadNotificationFactoryFactory: Provider<DownloadNotificationFactory.Factory>,
	private val deleteReadChaptersUseCase: Provider<DeleteReadChaptersUseCase>,
) : WorkerFactory() {

	override fun createWorker(
		appContext: Context,
		workerClassName: String,
		workerParameters: WorkerParameters,
	): ListenableWorker? = when (workerClassName) {
		DownloadWorker::class.java.name -> DownloadWorker(
			appContext = appContext,
			params = workerParameters,
			okHttp = okHttp.get(),
			cache = cache.get(),
			localMangaRepository = localMangaRepository.get(),
			mangaLock = mangaLock.get(),
			mangaDataRepository = mangaDataRepository.get(),
			mangaRepositoryFactory = mangaRepositoryFactory.get(),
			settings = settings.get(),
			localStorageChanges = localStorageChanges.get(),
			slowdownDispatcher = slowdownDispatcher.get(),
			imageProxyInterceptor = imageProxyInterceptor.get(),
			notificationFactoryFactory = downloadNotificationFactoryFactory.get(),
		)

		LocalStorageCleanupWorker::class.java.name -> LocalStorageCleanupWorker(
			appContext = appContext,
			params = workerParameters,
			settings = settings.get(),
			localMangaRepository = localMangaRepository.get(),
			dataRepository = mangaDataRepository.get(),
			deleteReadChaptersUseCase = deleteReadChaptersUseCase.get(),
		)

		else -> null
	}
}
