package com.artemchep.keyguard.android.glide.picture

import android.net.Uri
import com.artemchep.keyguard.android.glide.util.combineModelLoaders
import com.artemchep.keyguard.feature.favicon.PictureUrl
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

class PictureLoader : ModelLoader<PictureUrl, Uri> {
    class Factory : ModelLoaderFactory<PictureUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) = kotlin.run {
            val aModelLoader = PictureLoader()
            val bModelLoader = multiFactory.build(Uri::class.java, InputStream::class.java)
            combineModelLoaders(
                aModelLoader,
                bModelLoader,
            )
        }

        override fun teardown() {
            // Do nothing.
        }
    }

    override fun buildLoadData(
        model: PictureUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<Uri>? {
        return ModelLoader.LoadData(
            ObjectKey(model.url),
            Fetcher(
                model = model,
            ),
        )
    }

    override fun handles(model: PictureUrl): Boolean = true

    class Fetcher(
        private val model: PictureUrl,
    ) : DataFetcher<Uri> {
        override fun loadData(
            priority: Priority,
            callback: DataFetcher.DataCallback<in Uri>,
        ) {
            kotlin.runCatching {
                val finalUrl = model.url
                finalUrl.let { Uri.parse(it) }
            }.fold(
                onFailure = { e ->
                    if (e is Exception) {
                        callback.onLoadFailed(e)
                    } else {
                        callback.onLoadFailed(IllegalStateException(e))
                    }
                },
                onSuccess = { imageUrl ->
                    if (imageUrl != null) {
                        callback.onDataReady(imageUrl)
                    } else {
                        callback.onLoadFailed(NullPointerException())
                    }
                },
            )
        }

        override fun cleanup() {
        }

        override fun cancel() {
        }

        override fun getDataClass(): Class<Uri> = Uri::class.java

        override fun getDataSource(): DataSource = DataSource.REMOTE
    }
}
