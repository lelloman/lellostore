package com.lelloman.store

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageResult
import com.lelloman.store.worker.WorkManagerInitializer
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class LelloStoreApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workManagerInitializer: WorkManagerInitializer

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        workManagerInitializer.initialize()
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = okHttpClient))
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        Log.d("CoilDebug", "Loading image: ${chain.request.data}")
                        val result = chain.proceed()
                        when (result) {
                            is coil3.request.SuccessResult -> Log.d("CoilDebug", "Success loading image")
                            is coil3.request.ErrorResult -> Log.d("CoilDebug", "Error: ${result.throwable.message}")
                        }
                        return result
                    }
                })
            }
            .build()
    }
}
