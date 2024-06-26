package com.artemchep.keyguard.common.service.relays.api.simplelogin

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.service.relays.api.EmailRelaySchema
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.provider.bitwarden.api.builder.ensureSuffix
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.di.DirectDI
import org.kodein.di.instance

class SimpleLoginEmailRelay(
    private val httpClient: HttpClient,
) : EmailRelay {
    companion object {
        private const val ENDPOINT_BASE_URL = "https://app.simplelogin.io/"
        private const val ENDPOINT_PATH = "api/alias/random/new"

        private const val KEY_API_KEY = "apiKey"
        private const val HINT_API_KEY =
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        private const val KEY_MODE = "mode"
        private const val KEY_BASE_URL = "base_url"
        private const val HINT_BASE_URL = ENDPOINT_BASE_URL
    }

    override val type = "SimpleLogin"

    override val name = "SimpleLogin"

    override val docUrl =
        "https://bitwarden.com/help/generator/#tab-simplelogin-3Uj911RtQsJD9OAhUuoKrz"

    override val schema = persistentMapOf(
        KEY_API_KEY to EmailRelaySchema(
            title = TextHolder.Res(Res.string.api_key),
            hint = TextHolder.Value(HINT_API_KEY),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Token,
            canBeEmpty = false,
        ),
        KEY_BASE_URL to EmailRelaySchema(
            title = TextHolder.Res(Res.string.emailrelay_base_env_server_url_label),
            hint = TextHolder.Value(HINT_BASE_URL),
            description = TextHolder.Res(Res.string.emailrelay_base_env_note),
            canBeEmpty = true,
        ),
    )

    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(),
    )

    override fun generate(
        context: GeneratorContext,
        config: ImmutableMap<String, String>,
    ): IO<String> = ioEffect {
        val apiKey = requireNotNull(config[KEY_API_KEY]) {
            "API key is required for creating an email alias."
        }
        val apiUrl = kotlin.run {
            val baseUrl = config[KEY_BASE_URL]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: ENDPOINT_BASE_URL
            baseUrl.ensureSuffix("/") + ENDPOINT_PATH
        }
        // Create a new random alias.
        //
        // - Authentication header that contains the api key
        // - (Optional, query) "hostname"
        // - (Optional, query) "mode": either 'uuid' or 'word'. By default, use the user setting when creating new random alias.
        //
        // Request Message Body in json (Content-Type is application/json)
        // - (Optional) note: alias note
        //
        // https://github.com/simple-login/app/blob/master/docs/api.md#post-apialiasrandomnew
        val response = httpClient
            .post(apiUrl) {
                header("Authentication", apiKey)

                // Optional parameters.
                val hostname = context.host
                if (hostname != null) {
                    parameter("hostname", hostname)
                }
                val mode = config[KEY_MODE]
                if (mode != null) {
                    parameter("mode", mode)
                }
            }
        require(response.status.isSuccess()) {
            // Example of an error:
            // {"error":"Wrong api key"}
            val message = runCatching {
                val result = response
                    .body<JsonObject>()
                result["error"]?.jsonPrimitive?.content
            }.getOrNull()
            message ?: HttpStatusCode.fromValue(response.status.value).description
        }
        val result = response
            .body<JsonObject>()
        val alias = result["alias"]?.jsonPrimitive?.content
        requireNotNull(alias) {
            "Email alias is missing from the response. " +
                    "Please report this to the developer."
        }
    }
}
